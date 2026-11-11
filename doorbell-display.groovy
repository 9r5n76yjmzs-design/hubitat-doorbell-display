/**
 * Doorbell Display
 *
 * When the doorbell rings, switches each chosen ViewPort/TV pair to the doorbell camera: the UniFi Protect
 * ViewPort shows a chosen liveview (for example "Doorbell") and the LG TV it is connected to switches to
 * the ViewPort's input. After a set time, both go back to what they showed before the ring, unless someone
 * took control in the meantime.
 *
 * A pair whose TV is off is left alone: the app never turns a TV on.
 *
 * Uses the UniFi Protect Doorbell and Viewport drivers from
 * https://github.com/9r5n76yjmzs-design/hubitat-unifi-protect and the LGTV with webOS driver from
 * https://github.com/9r5n76yjmzs-design/hubitat-lg-webos
 *
 * Each installed copy of the app is one automation; install it again for another doorbell.
 *
 * @see https://github.com/9r5n76yjmzs-design/hubitat-doorbell-display
 */
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String APP_NAME = 'Doorbell Display'
@Field static final String APP_VERSION = '0.2.0'
@Field static final Integer DEFAULT_DISPLAY_SECONDS = 30
// Extra time after the display ends during which rings are still ignored, so a restore that is a little late
// isn't overlapped by a new display.
@Field static final Integer RESTORE_MARGIN_SECONDS = 5
@Field static final List<String> UNKNOWN_VALUES = ['unknown', 'none', 'off', '']
// The TV counts as on when its switch is on and the screen isn't off (audio only) or in standby.
// The screen value isn't reset when the TV powers off, so the switch is checked too.
@Field static final List<String> SCREEN_OFF_VALUES = ['off', 'standby']
// TV changes that mean someone took control during the display. Volume and mute are not watched:
// turning the sound up to hear the doorbell camera doesn't mean they want to stay on the ViewPort.
@Field static final List<String> WATCHED_TV_ATTRIBUTES = ['currentActivity', 'switch', 'screen', 'channel']
// How often the settings page may ask a TV for its input list on its own (the Reload inputs button always asks).
@Field static final Long INPUT_REQUEST_INTERVAL_MS = 60000L

definition(
    name: APP_NAME,
    namespace: 'dailous',
    author: 'Michael Dailous',
    description: 'Shows the doorbell camera on UniFi Protect ViewPorts and the LG TVs they are connected to when the doorbell rings, then restores what each showed before.',
    category: 'Convenience',
    iconUrl: '',
    iconX2Url: '',
    importUrl: 'https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-doorbell-display/main/doorbell-display.groovy',
    singleInstance: false
)

preferences {
    page(name: 'mainPage')
}

Map mainPage() {
    dynamicPage(name: 'mainPage', title: APP_NAME, install: true, uninstall: true) {
        section {
            label title: 'Name for this automation', required: false
        }
        section('Doorbell and ViewPorts') {
            input 'doorbell', 'device.UniFiProtectDoorbell', title: 'Doorbell', required: true
            input 'viewports', 'device.UniFiProtectViewport', title: 'ViewPorts to switch', multiple: true, required: true, submitOnChange: true
        }
        viewports?.each { vp ->
            section("TV connected to ${vp.displayName}") {
                input tvSetting(vp), 'device.LGTVwithwebOS', title: 'TV', required: true, submitOnChange: true
                def tv = settings[tvSetting(vp)]
                if (tv) {
                    List<String> inputs = tvInputChoices(tv, settings[tvInputSetting(vp)] as String)
                    if (inputs) {
                        input tvInputSetting(vp), 'enum', title: "Input on ${tv.displayName} that ${vp.displayName} is connected to",
                            options: inputs, required: true
                    } else if (requestTvInputs(tv)) {
                        paragraph "Asking ${tv.displayName} for its inputs. Wait a few seconds, then click Reload inputs."
                    } else {
                        paragraph "${tv.displayName} is off, so it can't list its inputs. Turn it on, then click Reload inputs."
                    }
                    input "reloadInputs_${vp.id}", 'button', title: 'Reload inputs'
                }
            }
        }
        section('What to show') {
            List<String> choices = liveviewChoices()
            if (choices) {
                input 'liveview', 'enum', title: 'Liveview to show when the doorbell rings', options: choices,
                    defaultValue: defaultLiveview(choices), required: true
            } else if (viewports) {
                paragraph 'The selected ViewPorts have no liveview list. Check that the UniFi Protect Controller shows apiStatus ok, then reopen this page.'
            } else {
                paragraph 'Choose the ViewPorts first; their liveviews are listed here.'
            }
            input 'displaySeconds', 'number', title: 'Put everything back after (seconds)', range: '5..3600',
                defaultValue: DEFAULT_DISPLAY_SECONDS, required: true
            paragraph 'A ViewPort and its TV are switched only when that TV is on. Rings during the display are ignored and don\'t restart the timer. ' +
                'When the time is up, a ViewPort goes back unless someone changed its liveview, and a TV goes back only if it is still on the ViewPort input and nobody changed its input, channel, power or screen in the meantime.'
        }
        section('Logging') {
            input 'logEnable', 'bool', title: 'Enable debug logging (turns off after 30 minutes)', defaultValue: false
            input 'txtEnable', 'bool', title: 'Enable description text logging', defaultValue: true
        }
    }
}

// ===================================================================================================================
// Lifecycle
// ===================================================================================================================

void installed() {
    log.info "${app.label ?: APP_NAME}: Installed ${APP_NAME} ${APP_VERSION}"
    initialize()
}

void updated() {
    log.info "${app.label ?: APP_NAME}: Settings saved"
    unsubscribe()
    unschedule('logsOff')
    if (logEnable) runIn 1800, 'logsOff'
    initialize()
}

// Debug logging switches itself off after 30 minutes, so it isn't left on by accident.
void logsOff() {
    app.updateSetting 'logEnable', [value:'false', type:'bool']
    log.info "${app.label ?: APP_NAME}: Debug logging disabled"
}

void initialize() {
    // A restore that is already scheduled is kept, so saving settings during a ring doesn't strand a display.
    subscribe(doorbell, 'pushed', 'ringHandler')
    if (state.active == true) subscribeToSessionTvs((atomicState.sessions ?: [:]) as Map)
}

// ===================================================================================================================
// Ring and restore
// ===================================================================================================================

void ringHandler(evt) {
    if ("${evt.value}" != '1') return
    String target = settings.liveview as String
    if (!target) {
        log.warn "${app.label ?: APP_NAME}: the doorbell rang, but no liveview is chosen; open the app and choose one"
        return
    }
    String doorbellName = doorbell?.displayName ?: 'Doorbell'
    // Rings during the display are ignored: the timer is not restarted. The deadline (with a small margin)
    // means a restore that never ran, for example after a hub reboot, can't block later rings.
    if (state.active == true && now() < ((state.activeUntil ?: 0L) as Long)) {
        log_info "${doorbellName} rang again while '${target}' is showing; ignored"
        return
    }
    Integer seconds = (displaySeconds ?: DEFAULT_DISPLAY_SECONDS) as Integer
    Map sessions = [:]
    viewports?.each { vp ->
        Map session = startPair(vp, target)
        if (session) sessions[vp.id as String] = session
    }
    if (!sessions) {
        log_info "${doorbellName} rang, but no ViewPort's TV is on; nothing was switched"
        return
    }
    atomicState.sessions = sessions
    state.active = true
    state.activeUntil = now() + (seconds + RESTORE_MARGIN_SECONDS) * 1000L
    subscribeToSessionTvs(sessions)
    log_info "${doorbellName} rang: showing '${target}' on ${sessions.size()} ViewPort/TV pair(s) for ${seconds} seconds"
    runIn seconds, 'restoreDisplay'
}

// Switches one ViewPort and its TV, and returns what is needed to put them back, or null when the pair is skipped.
private Map startPair(vp, String target) {
    def tv = settings[tvSetting(vp)]
    String tvInput = settings[tvInputSetting(vp)] as String
    if (!tv || !tvInput) {
        log.warn "${app.label ?: APP_NAME}: ${vp.displayName} has no TV or TV input chosen; open the app and choose them"
        return null
    }
    if (!tvIsOn(tv)) {
        log_info "${tv.displayName} is off; leaving ${vp.displayName} and ${tv.displayName} alone"
        return null
    }

    // ViewPort: remember its liveview, unless it already shows the target or its value is unknown.
    String liveviewName = vp.currentValue('liveview', true) as String
    String liveviewId = vp.currentValue('liveviewId', true) as String
    String vpRestore = isKnown(liveviewId) && !sameText(liveviewName, target) ? liveviewId : ''
    log_debug "${vp.displayName} was showing '${liveviewName}'; ${vpRestore ? 'it will be put back' : 'nothing to put back'}"
    if (!sameText(liveviewName, target)) vp.setLiveview(target)

    // TV: remember what it shows, unless it is already on the ViewPort input or its value is unknown.
    String activity = tv.currentValue('currentActivity', true) as String
    boolean alreadyThere = sameText(activity, tvInput)
    String tvRestore = isKnown(activity) && !alreadyThere ? activity : ''
    log_debug "${tv.displayName} was showing '${activity}'; ${tvRestore ? 'it will be put back' : 'nothing to put back'}"
    if (!alreadyThere) tv.startActivity(tvInput)

    return [vpRestore:vpRestore, tvId:tv.id as String, tvInput:tvInput, tvRestore:tvRestore,
            tvArrived:alreadyThere, tvTouched:'']
}

// Watches each TV in the display for changes the app didn't make, which mean someone took control.
void tvEventHandler(evt) {
    if (state.active != true) return
    Map sessions = (atomicState.sessions ?: [:]) as Map
    boolean changed = false
    sessions.each { String vpId, Map s ->
        if (s.tvId != (evt.deviceId as String) || s.tvTouched) return
        String value = evt.value as String
        String reason = null
        switch (evt.name) {
            case 'currentActivity':
                if (sameText(value, s.tvInput as String)) {
                    // The app's own switch arriving, however long the TV took to report it.
                    if (!s.tvArrived) { s.tvArrived = true; changed = true }
                } else if (s.tvArrived) {
                    // Before the switch arrives, the TV may still report what it showed or an in-between screen.
                    reason = "input changed to '${value}'"
                }
                break
            case 'switch':
                if (value == 'off') reason = 'turned off'
                break
            case 'screen':
                if (value in SCREEN_OFF_VALUES) reason = "screen turned ${value}"
                break
            case 'channel':
                if (s.tvArrived) reason = "channel changed to ${value}"
                break
        }
        if (reason) {
            s.tvTouched = reason
            changed = true
            log_info "${evt.displayName ?: 'TV'} ${reason} during the display; it won't be switched back"
        }
    }
    if (changed) atomicState.sessions = sessions
}

void restoreDisplay() {
    Map sessions = (atomicState.sessions ?: [:]) as Map
    String target = settings.liveview as String
    viewports?.each { vp ->
        Map s = sessions[vp.id as String] as Map
        if (!s) return
        restoreViewport(vp, s.vpRestore as String, target)
        restoreTv(settings[tvSetting(vp)], s)
    }
    atomicState.sessions = [:]
    state.remove('activeUntil')
    state.active = false
    unsubscribe()
    subscribe(doorbell, 'pushed', 'ringHandler')
}

private void restoreViewport(vp, String savedId, String target) {
    if (!savedId) return
    String currentName = vp.currentValue('liveview', true) as String
    if (!sameText(currentName, target)) {
        log_info "${vp.displayName} was changed to '${currentName}' meanwhile; leaving it"
        return
    }
    log_info "Putting ${vp.displayName} back to its previous liveview"
    vp.setLiveview(savedId)
}

// Puts the TV back only when all of these hold: there is something to go back to, nobody took control,
// the TV is still on, and it still shows the ViewPort input.
private void restoreTv(tv, Map s) {
    if (!tv || (tv.id as String) != s.tvId) return
    String previous = s.tvRestore as String
    if (!previous) {
        log_debug "${tv.displayName}: nothing to put back"
        return
    }
    if (s.tvTouched) {
        log_info "${tv.displayName} ${s.tvTouched} during the display; leaving it"
        return
    }
    if (!tvIsOn(tv)) {
        log_info "${tv.displayName} is off; leaving it off"
        return
    }
    String current = tv.currentValue('currentActivity', true) as String
    if (!sameText(current, s.tvInput as String)) {
        log_info "${tv.displayName} shows '${current}', not the ViewPort input; leaving it"
        return
    }
    log_info "Putting ${tv.displayName} back to '${previous}'"
    tv.startActivity(previous)
}

private void subscribeToSessionTvs(Map sessions) {
    Set<String> tvIds = sessions.values().collect { it.tvId as String } as Set
    viewports?.each { vp ->
        def tv = settings[tvSetting(vp)]
        if (!tv || !tvIds.remove(tv.id as String)) return // each TV once
        WATCHED_TV_ATTRIBUTES.each { String name -> subscribe(tv, name, 'tvEventHandler') }
    }
}

// ===================================================================================================================
// Helpers
// ===================================================================================================================

private static String tvSetting(vp) {
    return "tv_${vp.id}"
}

private static String tvInputSetting(vp) {
    return "tvInput_${vp.id}"
}

private static boolean tvIsOn(tv) {
    String screen = tv.currentValue('screen', true) as String
    return (tv.currentValue('switch', true) as String) == 'on' && !(screen in SCREEN_OFF_VALUES)
}

// Settings page buttons. Reload inputs asks the TV for a fresh input list; the page then redraws.
void appButtonHandler(String button) {
    if (!button?.startsWith('reloadInputs_')) return
    String vpId = button.substring('reloadInputs_'.length())
    def vp = viewports?.find { (it.id as String) == vpId }
    def tv = vp ? settings[tvSetting(vp)] : null
    if (tv) requestTvInputs(tv, true)
}

// Asks the TV to list its inputs and apps (Get All Activities). The answer arrives a few seconds later in the
// TV's activities attribute. Only works while the TV is on. Without force, asks at most once a minute per TV,
// so redrawing the page doesn't flood the TV. Returns false when the TV is off.
private boolean requestTvInputs(tv, boolean force = false) {
    if ((tv.currentValue('switch', true) as String) != 'on') return false
    Map asked = (state.inputsRequested ?: [:]) as Map
    String key = tv.id as String
    Long last = (asked[key] ?: 0L) as Long
    if (force || now() - last > INPUT_REQUEST_INTERVAL_MS) {
        log_debug "Asking ${tv.displayName} for its inputs"
        tv.getAllActivities()
        asked[key] = now()
        state.inputsRequested = asked
    }
    return true
}

// Inputs and apps offered for a TV, from its activities attribute (a JSON list filled by Get All Activities).
private List<String> tvInputChoices(tv, String saved) {
    Set<String> names = [] as Set
    names.addAll(jsonList(tv.currentValue('activities') as String, tv.displayName as String))
    // Keep the saved choice visible even while the TV is off and its list is empty.
    if (saved) names << saved
    return names.sort()
}

// Liveview names offered for the selected ViewPorts, from their liveviews attribute (a JSON list).
private List<String> liveviewChoices() {
    Set<String> names = [] as Set
    viewports?.each { vp -> names.addAll(jsonList(vp.currentValue('liveviews') as String, vp.displayName as String)) }
    // Keep the saved choice visible even while Protect can't be reached and the lists are empty.
    if (settings.liveview) names << (settings.liveview as String)
    return names.sort()
}

private List<String> jsonList(String json, String deviceName) {
    if (!json) return []
    try {
        Object list = new JsonSlurper().parseText(json)
        if (list instanceof List) return list.findAll { it != null }.collect { it as String }
    } catch (e) {
        log_debug "${deviceName} has an unreadable list value"
    }
    return []
}

// Pre-selects a liveview named like "Doorbell", so a typical setup needs no extra click.
private static String defaultLiveview(List<String> choices) {
    return choices.find { it.equalsIgnoreCase('doorbell') } ?: choices.find { it.toLowerCase().contains('doorbell') }
}

private static boolean isKnown(String value) {
    return value != null && !(value.trim().toLowerCase() in UNKNOWN_VALUES)
}

private static boolean sameText(String a, String b) {
    return a != null && b != null && a.trim().equalsIgnoreCase(b.trim())
}

private void log_debug(String message) {
    if (logEnable) log.debug "${app.label ?: APP_NAME}: ${message}"
}

private void log_info(String message) {
    if (txtEnable != false) log.info "${app.label ?: APP_NAME}: ${message}"
}
