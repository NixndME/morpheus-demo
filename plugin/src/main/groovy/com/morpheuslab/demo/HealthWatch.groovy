package com.morpheuslab.demo

import com.morpheusdata.core.data.DataQuery
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/**
 * Every 30 s: reads health.php of each demo app that picked OpsRamp, raises or clears the
 * "Database down" and "Web tier down" alerts in OpsRamp, and assigns the OpsRamp OS template once.
 */
@Slf4j
class HealthWatch {
    static final Map<String, Boolean> DB_UP = new ConcurrentHashMap<>()
    static final Map<String, Boolean> WEB_UP = new ConcurrentHashMap<>()
    static final Map<String, Map> LAST = new ConcurrentHashMap<>()
    // apps being deleted: their VMs look running for a while, leave them alone
    static final Set<String> GONE = ConcurrentHashMap.newKeySet()
    // OpsRamp apps seen while running; when one disappears (deleted or expired), OpsRamp is cleaned up
    static final Set<String> SEEN = ConcurrentHashMap.newKeySet()

    DemoPlugin plugin
    Timer timer

    void start() {
        timer = new Timer('morpheus-demo-health', true)
        timer.schedule({ safeRun() } as TimerTask, 20000L, 30000L)
    }

    void stop() { timer?.cancel() }

    private void safeRun() {
        try { run() } catch (Throwable t) { log.warn("Morpheus Demo: health check failed: ${t.message}") }
    }

    /** The order values of an instance, without the password. */
    static Map options(Object inst) {
        try {
            Map c = inst.config ? new JsonSlurper().parseText(inst.config as String) as Map : [:]
            // values the order left empty arrive as the word null
            ((c.customOptions ?: [:]) as Map).findAll { k, v -> !(k as String).toLowerCase().contains('password') && v != null && v != 'null' }
        } catch (Throwable ignored) { [:] }
    }

    /** Live state of one app from its web tier, or [web: down]. */
    static Map health(String ip) {
        if (!ip) return [web: [up: false], db: [up: false]]
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://${ip}/health.php").openConnection()
            c.connectTimeout = 3000
            c.readTimeout = 5000
            Map h = new JsonSlurper().parse(c.inputStream) as Map
            h
        } catch (Throwable ignored) {
            [web: [up: false], db: [up: false]]
        }
    }

    /** The VM's IP from its server record; the instance model often has none. */
    private String ipOf(String name) {
        def srv = plugin.morpheus.services.computeServer.find(new DataQuery().withFilter('name', name))
        String ip = srv?.internalIp ?: srv?.externalIp ?: srv?.sshHost
        ip && ip != '0.0.0.0' ? ip : null
    }

    void run() {
        OpsRamp op = OpsRamp.of(plugin.settingsMap())
        List insts = plugin.morpheus.services.instance.list(new DataQuery()).findAll { it.site?.name == Setup.GROUP }
        Set<String> apps = insts.collect { (it.name as String).replaceAll(/-(web|db)$/, '') } as Set
        if (op) (SEEN - apps).each { gone ->
            try { op.dropApp(gone) } catch (Throwable t) { log.warn("Morpheus Demo: OpsRamp clean up of ${gone}: ${t.message}") }
            SEEN.remove(gone)
            GONE.remove(gone)
        }
        insts.findAll { (it.name as String).endsWith('-web') && it.status == 'running' }.each { web ->
            Map o = options(web)
            String app = (web.name as String).replaceAll(/-web$/, '')
            if (app in GONE) return
            String ip = ipOf(web.name as String)
            if (!ip) return
            Map h = health(ip)
            h.ms = System.currentTimeMillis()
            LAST[app] = h
            if (o.demoMonitoring != 'opsramp' || !op) return
            SEEN << app
            [app + '-web', app + '-db'].each { host ->
                try { op.monitor(host) } catch (Throwable t) { log.warn("Morpheus Demo: OpsRamp templates on ${host}: ${t.message}") }
            }
            try { op.serviceMap(app) } catch (Throwable t) { log.warn("Morpheus Demo: service map for ${app}: ${t.message}") }
            boolean webUp = h.web?.up as boolean, dbUp = h.db?.up as boolean
            if (WEB_UP.containsKey(app) ? WEB_UP[app] != webUp : !webUp) {
                op.alert(app + '-web', 'apache', "Web tier down on ${app}-web", "The web page of ${app} does not answer.", !webUp)
            }
            WEB_UP[app] = webUp
            if (webUp && (DB_UP.containsKey(app) ? DB_UP[app] != dbUp : !dbUp)) {
                op.alert(app + '-db', 'mariadb', "Database down on ${app}-db",
                    dbUp ? "MariaDB on ${app}-db answers again." : "The web tier of ${app} cannot reach MariaDB on ${app}-db.", !dbUp)
            }
            if (webUp) DB_UP[app] = dbUp
        }
    }
}
