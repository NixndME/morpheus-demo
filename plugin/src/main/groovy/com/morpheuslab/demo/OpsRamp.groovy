package com.morpheuslab.demo

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/** Small OpsRamp REST client: monitoring templates and open alerts of the demo VMs. */
@Slf4j
class OpsRamp {
    // CPU, memory and disk; the database check comes from HealthWatch as alerts
    static final List<String> TEMPLATES = ['Agent G2 - Linux OS Performance Monitoring']
    static final Map<String, Boolean> ASSIGNED = new ConcurrentHashMap<>()
    static final Map<String, Object> TOKEN = new ConcurrentHashMap<>()

    String server, tenant, key, secret

    static OpsRamp of(Map s) {
        String host = (s.opsrampServer as String)?.replaceAll('^https?://', '')?.replaceAll('/.*$', '')
        if (!(host && s.opsrampTenant && s.opsrampApiKey && s.opsrampApiSecret)) return null
        new OpsRamp(server: host, tenant: s.opsrampTenant, key: s.opsrampApiKey, secret: s.opsrampApiSecret)
    }

    private String token() {
        if (TOKEN.value && (TOKEN.until as Long) > System.currentTimeMillis()) return TOKEN.value
        HttpURLConnection c = (HttpURLConnection) new URL("https://${server}/auth/oauth/token").openConnection()
        c.requestMethod = 'POST'
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.doOutput = true
        c.setRequestProperty('Content-Type', 'application/x-www-form-urlencoded')
        c.outputStream.withWriter { it << "grant_type=client_credentials&client_id=${enc(key)}&client_secret=${enc(secret)}" }
        Map r = new JsonSlurper().parse(c.inputStream) as Map
        TOKEN.value = r.access_token
        TOKEN.until = System.currentTimeMillis() + ((r.expires_in ?: 3600) as Long) * 1000L - 60000L
        r.access_token
    }

    // OpsRamp reads + as a plus, not a space
    private static String enc(Object v) { URLEncoder.encode(v as String, 'UTF-8').replace('+', '%20') }

    Object req(String method, String path, Object body = null) {
        HttpURLConnection c = (HttpURLConnection) new URL("https://${server}/api/v2/tenants/${tenant}${path}").openConnection()
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 20000
        c.setRequestProperty('Authorization', 'Bearer ' + token())
        c.setRequestProperty('Accept', 'application/json')
        c.setRequestProperty('Content-Type', 'application/json')
        if (body != null) { c.doOutput = true; c.outputStream.withWriter('UTF-8') { it << JsonOutput.toJson(body) } }
        int code = c.responseCode
        String text = (code < 400 ? c.inputStream : c.errorStream)?.getText('UTF-8') ?: ''
        if (code >= 400) throw new IOException("OpsRamp ${code}")
        text ? new JsonSlurper().parseText(text) : [:]
    }

    /** The OpsRamp resource of a host name, or null. */
    Map resource(String host) {
        Map r = req('GET', '/resources/search?queryString=' + enc("hostName:${host}")) as Map
        (r.results as List)?.find { it.hostName == host || it.name == host } as Map
    }

    /** Assigns the demo templates once per resource. Returns true when done. */
    boolean monitor(String host) {
        if (ASSIGNED[host]) return true
        Map res = resource(host)
        if (!res) return false
        List ids = TEMPLATES.collect { n ->
            Map t = req('GET', '/monitoring/templates/search?queryString=' + enc("name:${n}") + '&pageSize=5') as Map
            (t.results as List)?.find { it.name == n }?.id
        }.findAll()
        Set have = ((req('GET', "/resources/${res.id}/templates/search") as Map).results as List ?: []).collect { it.id } as Set
        ids.findAll { !(it in have) }.each { req('POST', "/resources/${res.id}/templates", [[id: it]]) }
        ASSIGNED[host] = true
        log.info("Morpheus Demo: OpsRamp templates on ${host}")
        true
    }

    /** The service map of an app: "Morpheus Demo: <app>" with a web tier and a database tier. Made once. */
    void serviceMap(String app) {
        String key = 'map:' + app
        if (ASSIGNED[key]) return
        Map web = resource(app + '-web'), db = resource(app + '-db')
        if (!web || !db) return
        String top = group("Morpheus Demo: ${app}", null, 'SERVICEGROUP')
        [['Web tier', web], ['Database tier', db]].each { t ->
            String gid = group("${app} ${t[0]}", top, 'DEVICE')
            // answers an error when the VM is already in the tier
            try { req('POST', "/serviceGroups/${gid}/childs", [[id: t[1].id]]) } catch (IOException ignored) { }
        }
        ASSIGNED[key] = true
        log.info("Morpheus Demo: OpsRamp service map for ${app}")
    }

    /** Deletes the service map of an app. */
    void dropServiceMap(String app) {
        String top = findGroup("Morpheus Demo: ${app}")
        if (!top) return
        ["${app} Web tier", "${app} Database tier"].each { n ->
            String id = childGroup(top, n as String)
            if (id) req('DELETE', "/serviceGroups/${id}")
        }
        req('DELETE', "/serviceGroups/${top}")
        ASSIGNED.remove('map:' + app)
    }

    /** Deletes what OpsRamp has of an app: its service map and the resources of both VMs. */
    void dropApp(String app) {
        dropServiceMap(app)
        [app + '-web', app + '-db'].each { host ->
            Map r = resource(host)
            if (r) req('DELETE', "/resources/${r.id}")
            ASSIGNED.remove(host)
        }
        log.info("Morpheus Demo: OpsRamp cleaned up ${app}")
    }

    // the name search is fuzzy, so compare the name exactly
    private String findGroup(String name) {
        Map r = req('GET', '/serviceGroups/search?queryString=' + enc("name:${name}")) as Map
        (r.results as List)?.find { it.name == name }?.id
    }

    // tier groups are not found by the name search, only through their parent
    private String childGroup(String parent, String name) {
        Map r = req('GET', "/serviceGroups/${parent}/childs/search") as Map
        (r.results as List)?.find { it.name == name }?.id
    }

    private String group(String name, String parent, String childType) {
        String id = parent ? childGroup(parent, name) : findGroup(name)
        if (id) return id
        Map body = [name: name, childType: childType]
        if (parent) body.parent = [id: parent]
        ((req('POST', '/serviceGroups', [body]) as List)?.first() as Map)?.id
    }

    /** Raises (critical) or clears (ok) one alert on a host. */
    void alert(String host, String component, String subject, String text, boolean critical) {
        Map res = resource(host)
        Map body = [serviceName: component, component: component, subject: subject, description: text, alertType: 'Monitoring',
                    currentState: critical ? 'CRITICAL' : 'OK', app: 'OPSRAMP', device: [hostName: host]]
        if (res) body.device.resourceUUID = res.id
        req('POST', '/alert', body)
        log.info("Morpheus Demo: OpsRamp alert ${critical ? 'raised' : 'cleared'} on ${host}: ${subject}")
    }

    /** Open (not Ok) alerts of the last day for these host names. */
    List<Map> alerts(Collection<String> hosts) {
        if (!hosts) return []
        String since = new Date(System.currentTimeMillis() - 86400000L).format("yyyy-MM-dd'T'HH:mm:ss'+0000'", TimeZone.getTimeZone('UTC'))
        Map r = req('GET', '/alerts/search?pageSize=200&queryString=' + enc("startDate:${since}")) as Map
        (r.results as List ?: []).findAll { a ->
            String n = a.device?.name ?: a.resource?.name
            // a fresh VM always has a low uptime alert, and page cache use is healthy; neither says anything about the app
            n in hosts && a.currentState != 'Ok' && a.metric != 'system.os.uptime' && !((a.subject ?: '') as String).contains('cache.utilization')
        }.collect { a ->
            [host: a.device?.name ?: a.resource?.name, state: a.currentState, metric: a.metric, subject: a.subject,
             ms: parseMs(a.updatedTime ?: a.createdDate)]
        }
    }

    private static Long parseMs(Object t) {
        try { Date.parse("yyyy-MM-dd'T'HH:mm:ssZ", (t as String).replaceAll(/\.\d+/, '')).time } catch (Throwable ignored) { null }
    }
}
