/**
 * Doorbell Display
 *
 * When the doorbell rings, shows a chosen liveview (for example "Doorbell") on one or more
 * UniFi Protect ViewPorts. After a set time, puts back whatever each ViewPort showed before
 * the ring, so nobody is left wondering why the screen changed.
 *
 * Uses the UniFi Protect Doorbell and UniFi Protect Viewport drivers from
 * https://github.com/9r5n76yjmzs-design/hubitat-unifi-protect
 *
 * Each installed copy of the app is one automation; install it again for another doorbell.
 *
 * @see https://github.com/9r5n76yjmzs-design/hubitat-doorbell-display
 */
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String APP_NAME = 'Doorbell Display'
@Field static final String APP_VERSION = '0.1.0'
@Field static final Integer DEFAULT_DISPLAY_SECONDS = 30
// Extra time after the display ends during which rings are still ignored, so a restore that is a little late
// isn't overlapped by a new display.
@Field static final Integer RESTORE_MARGIN_SECONDS = 5
@Field static final List<String> UNKNOWN_VALUES = ['unknown', 'none']

definition(
    name: APP_NAME,
    namespace: 'dailous',
    author: 'Michael Dailous',
    description: 'Shows the doorbell camera on UniFi Protect ViewPorts when the doorbell rings, then restores what each ViewPort showed before.',
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
            input 'displaySeconds', 'number', title: 'Put the previous liveview back after (seconds)', range: '5..3600',
                defaultValue: DEFAULT_DISPLAY_SECONDS, required: true
            paragraph 'Rings while the doorbell is showing are ignored and don\'t restart the timer; a ring after the previous liveview is back starts a new display. If someone changes a ViewPort to a different liveview in the meantime, that ViewPort is left alone.'
        }
        section('Logging') {
            input 'logEnable', 'bool', title: 'Enable debug logging', defaultValue: false
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
    initialize()
}

void initialize() {
    // A restore that is already scheduled is kept, so saving settings during a ring doesn't strand a ViewPort.
    subscribe(doorbell, 'pushed', 'ringHandler')
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
    Map<String, String> saved = [:]
    viewports?.each { vp ->
        String key = vp.id as String
        String currentName = vp.currentValue('liveview', true) as String
        // Remember what this ViewPort shows now. An empty value means "don't restore".
        String currentId = vp.currentValue('liveviewId', true) as String
        if (isKnown(currentId) && !sameLiveview(currentName, target)) {
            saved[key] = currentId
            log_debug "${vp.displayName} was showing '${currentName}'; it will be put back"
        } else {
            saved[key] = ''
            log_debug "${vp.displayName} was showing '${currentName}'; nothing to put back"
        }
        if (!sameLiveview(currentName, target)) vp.setLiveview(target)
    }
    state.saved = saved
    state.active = true
    state.activeUntil = now() + (seconds + RESTORE_MARGIN_SECONDS) * 1000L
    log_info "${doorbellName} rang: showing '${target}' for ${seconds} seconds"
    runIn seconds, 'restoreViewports'
}

void restoreViewports() {
    Map<String, String> saved = (state.saved ?: [:]) as Map<String, String>
    String target = settings.liveview as String
    viewports?.each { vp ->
        String savedId = saved[vp.id as String]
        if (!savedId) return
        String currentName = vp.currentValue('liveview', true) as String
        if (!sameLiveview(currentName, target)) {
            log_info "${vp.displayName} was changed to '${currentName}' meanwhile; leaving it"
            return
        }
        log_info "Putting ${vp.displayName} back to its previous liveview"
        vp.setLiveview(savedId)
    }
    state.remove('saved')
    state.remove('activeUntil')
    state.active = false
}

// ===================================================================================================================
// Helpers
// ===================================================================================================================

// Liveview names offered for the selected ViewPorts, from their liveviews attribute (a JSON list).
private List<String> liveviewChoices() {
    Set<String> names = [] as Set
    viewports?.each { vp ->
        String json = vp.currentValue('liveviews') as String
        if (!json) return
        try {
            Object list = new JsonSlurper().parseText(json)
            if (list instanceof List) names.addAll(list.collect { it as String })
        } catch (e) {
            log_debug "${vp.displayName} has an unreadable liveviews value"
        }
    }
    // Keep the saved choice visible even while Protect can't be reached and the lists are empty.
    if (settings.liveview) names << (settings.liveview as String)
    return names.sort()
}

// Pre-selects a liveview named like "Doorbell", so a typical setup needs no extra click.
private static String defaultLiveview(List<String> choices) {
    return choices.find { it.equalsIgnoreCase('doorbell') } ?: choices.find { it.toLowerCase().contains('doorbell') }
}

private static boolean isKnown(String value) {
    return value && !(value in UNKNOWN_VALUES)
}

private static boolean sameLiveview(String a, String b) {
    return a != null && b != null && a.trim().equalsIgnoreCase(b.trim())
}

private void log_debug(String message) {
    if (logEnable) log.debug "${app.label ?: APP_NAME}: ${message}"
}

private void log_info(String message) {
    if (txtEnable != false) log.info "${app.label ?: APP_NAME}: ${message}"
}
