package com.morpheuslab.demo

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/** Reads everything the Demo Apps dashboard shows, with the signed-in user's rights. */
@Slf4j
class Collector {
    SelfApi api
    Map settings = [:]
    List<String> errors = []

    static final Map COLORS = [Dev: '#2f7fc1', Test: '#c7881a', Production: '#cc1f1a']

    private static String money(Object v) { '$' + new BigDecimal((v ?: 0).toString()).setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString() }

    private static String ago(Long t) {
        if (!t) return ''
        long m = Math.max(0L, (System.currentTimeMillis() - t) / 60000L as long)
        m < 1 ? 'just now' : m < 60 ? "${m} min ago" : m < 1440 ? "${(m / 60) as long} h ago" : "${(m / 1440) as long} d ago"
    }

    private static Long utc(Object iso) {
        if (!iso) return null
        try {
            def f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
            f.timeZone = TimeZone.getTimeZone('UTC')
            f.parse((iso as String).take(19)).time
        } catch (Throwable ignored) { null }
    }

    Map prices = [:]

    /** Price per month of one VM from the Demo prices: cores, memory GB and disk GB. */
    private BigDecimal monthly(Map t) {
        BigDecimal cores = (t.maxCores ?: t.plan?.maxCores ?: 1) as BigDecimal
        BigDecimal mem = ((t.maxMemory ?: 0) as BigDecimal) / 1073741824
        BigDecimal disk = ((t.maxStorage ?: 0) as BigDecimal) / 1073741824
        cores * (prices.cores ?: 0) + mem * (prices.memory ?: 0) + disk * (prices.storage ?: 0)
    }

    Map collect() {
        (api.get('/api/prices?max=50&phrase=Demo').prices as List ?: []).findAll { (it.name as String).startsWith('Demo ') }.each {
            prices[it.priceType] = (it.price ?: 0) as BigDecimal
        }
        Map v = [errors: errors, updatedMs: System.currentTimeMillis()]
        v.updated = new Date().format('HH:mm')
        v.links = [catalog: '/provisioning/catalog', approvals: '/operations/approvals',
                   opsramp: settings.opsrampDashboardUrl ?: settings.opsrampUrl]

        Map item = (api.get('/api/catalog-item-types?max=100&phrase=Demo').catalogItemTypes as List)?.find { it.name == Setup.ITEM } as Map
        v.setupMissing = item == null
        if (item) v.links.order = "/provisioning/catalog?type=${item.id}".toString()

        List insts = (api.get('/api/instances?max=200').instances as List ?: []).findAll { it.group?.name == Setup.GROUP }
        List apps = (api.get('/api/apps?max=200').apps as List ?: []).findAll { it.group?.name == Setup.GROUP }
        OpsRamp op = OpsRamp.of(settings)

        List allBackups = []
        try { allBackups = api.get('/api/backups?max=500').backups as List ?: [] } catch (Throwable ignored) { }
        List rows = apps.collect { a -> appRow(a, insts.findAll { (it.name as String).startsWith((a.name as String) + '-') }, allBackups) }
        rows.sort { a, b -> ((b.createdMs ?: 0) as Long) <=> ((a.createdMs ?: 0) as Long) }

        // OpsRamp alerts for the apps that picked OpsRamp
        List opHosts = rows.findAll { it.monitoring == 'OpsRamp' }.collectMany { r -> r.tiers.collect { it.name } }
        List opAlerts = []
        if (op && opHosts) {
            try { opAlerts = op.alerts(opHosts) } catch (Throwable t) { errors << 'Could not read OpsRamp alerts. Check the OpsRamp API key in the plugin settings.' }
        }
        rows.each { r ->
            List mine = opAlerts.findAll { a -> r.tiers.any { it.name == a.host } }
            if (r.monitoring == 'Grafana' && r.webIp) mine += grafanaAlerts(r.webIp as String, r.name as String)
            r.alerts = mine.collect { it + [when: ago(it.ms as Long)] }
            r.alertCount = mine.size()
            r.alertText = mine ? "${mine.size()} alert${mine.size() == 1 ? '' : 's'}".toString() : ''
        }
        v.apps = rows

        v.approvals = approvals(apps)
        int vms = insts.size()
        BigDecimal cost = rows.sum { it.costValue ?: 0 } as BigDecimal ?: 0
        int backups = rows.sum { it.backups ?: 0 } as Integer ?: 0
        v.kpi = [running: rows.count { it.state == 'Running' }, building: rows.count { it.state == 'Building' },
                 problems: rows.count { it.state in ['Problem', 'Failed'] }, waiting: v.approvals.size(), vms: vms,
                 cost: money(cost), alerts: rows.sum { it.alertCount ?: 0 } ?: 0, backups: backups]

        v.costByOwner = bars(rows, 'costOwner')
        v.costById = bars(rows, 'costId')
        v.hvm = hvm()
        v.activity = activity(rows)
        v
    }

    /** One app: both tiers, live health, order values, cost and steps. */
    private Map appRow(Map a, List tiers, List allBackups) {
        Map web = tiers.find { (it.name as String).endsWith('-web') } as Map
        Map db = tiers.find { (it.name as String).endsWith('-db') } as Map
        Map o = HealthWatch.options([config: groovy.json.JsonOutput.toJson((web ?: db)?.config ?: [:])])
        String webIp = (web?.connectionInfo as List)?.find { it.ip && it.ip != '0.0.0.0' }?.ip
        String dbIp = (db?.connectionInfo as List)?.find { it.ip && it.ip != '0.0.0.0' }?.ip
        Map h = [:]
        if (web?.status == 'running' && webIp) {
            Map last = HealthWatch.LAST[a.name as String]
            h = (last && System.currentTimeMillis() - (last.ms as Long) < 60000L) ? last : HealthWatch.health(webIp)
        }
        BigDecimal cost = tiers.sum { monthly(it) } as BigDecimal ?: 0
        List statuses = tiers.collect { it.status }
        String state = statuses.any { it in ['failed'] } ? 'Failed' :
            statuses.any { it in ['provisioning', 'pending', 'resizing'] } || tiers.size() < 2 ? 'Building' :
            statuses.every { it == 'running' } ? (h.db?.up ? 'Running' : (h.web?.up ? 'Problem' : (h ? 'Problem' : 'Starting'))) :
            statuses.any { it == 'stopped' } ? 'Stopped' : (statuses.first() ?: 'Unknown').toString().capitalize()
        Long exp = utc(web?.expireDate ?: db?.expireDate)
        String mon = [opsramp: 'OpsRamp', grafana: 'Grafana', none: 'None'][o.demoMonitoring] ?: 'None'
        String monUrl = mon == 'Grafana' && webIp ? "http://${webIp}:3000/d/demo-app".toString() : mon == 'OpsRamp' ? (settings.opsrampDashboardUrl ?: settings.opsrampUrl) : null
        int backups = allBackups.count { bk -> tiers.any { it.id == bk.instance?.id } } as int
        Long created = utc(a.dateCreated)
        [id: a.id, name: a.name, env: o.demoEnv ?: a.environment ?: '', envColor: COLORS[o.demoEnv] ?: '#7d8a94',
         owner: o.demoOwner ?: a.owner?.username ?: '', email: o.demoEmail ?: '', size: ((web ?: db)?.plan?.name ?: '').replace('Demo ', ''),
         monitoring: mon, monitoringUrl: monUrl, costOwner: o.demoCostOwner ?: '', costApprover: o.demoCostApprover ?: '', costId: o.demoCostId ?: '',
         notes: o.demoNotes ?: '', reason: o.demoProdReason ?: '', backup: ['1': 'Every day at midnight', '2': 'Every Sunday at midnight'][o.demoBackup] ?: 'No backup',
         state: state, stateClass: [Running: 'ok', Building: 'info', Starting: 'info', Problem: 'crit', Failed: 'crit', Stopped: 'warn'][state] ?: 'muted',
         webText: !h ? 'Waiting' : h.web?.up ? 'Up' : 'Down', webCls: !h ? 'info' : h.web?.up ? 'ok' : 'bad',
         dbText: !h ? 'Waiting' : h.db?.up ? (h.db?.ms != null ? "${h.db.ms} ms".toString() : 'Up') : 'Down', dbCls: !h ? 'info' : h.db?.up ? 'ok' : 'bad', visitors: h.visitors, requests: h.requests, avgMs: h.avgMs,
         webIp: webIp, dbIp: dbIp, appUrl: webIp ? "http://${webIp}/".toString() : null,
         costValue: cost, cost: money(cost), expiresMs: exp, expires: exp ? "${Math.max(0, ((exp - System.currentTimeMillis()) / 86400000L) as int)} days".toString() : 'Never',
         createdMs: created, created: ago(created), backups: backups,
         tiers: [db, web].findAll().collect { t -> tierRow(t) }]
    }

    private Map tierRow(Map t) {
        List steps = []
        try {
            Map p = (api.get("/api/instances/${t.id}/history?max=3").processes as List)?.find { it.processType?.code == 'postProvision' || it.processType?.name == 'Post-Provision' } as Map
            steps = (p?.events as List ?: []).findAll { it.processType?.code == 'executeTask' }.withIndex().collect { e, i ->
                String out = ((e.output ?: '') as String) + '\n' + ((e.error ?: '') as String)
                String last = out.readLines().findAll { it.trim() }.findAll { !(it =~ /(?i)needrestart|outdated|No (services|containers|user sessions|VM guests)|kernel seems|^\s*$/) }.with { it ? it.last().trim() : '' }
                String task = ((e.description ?: '') as String).replace('Demo ', '')
                [n: i + 1, status: e.status, cls: [complete: 'ok', failed: 'bad', running: 'info'][e.status] ?: 'muted',
                 text: (task + (last ? ': ' + last : (e.status == 'complete' ? '' : ': ' + e.status))).take(150)]
            }
        } catch (Throwable ignored) { }
        String ip = (t.connectionInfo as List)?.find { it.ip && it.ip != '0.0.0.0' }?.ip
        [id: t.id, name: t.name, tier: (t.name as String).endsWith('-web') ? 'Web' : 'Database', status: t.status,
         statusText: ((t.status ?: '') as String).capitalize(),
         statusClass: [running: 'ok', provisioning: 'info', pending: 'info', failed: 'crit', stopped: 'warn'][t.status] ?: 'muted',
         ip: ip ?: '', plan: t.plan?.name ?: '', cost: money(monthly(t)), steps: steps,
         software: (t.name as String).endsWith('-web') ? 'Apache, PHP' : 'MariaDB']
    }

    private List grafanaAlerts(String ip, String app) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://${ip}:3000/api/prometheus/grafana/api/v1/alerts").openConnection()
            c.connectTimeout = 2000
            c.readTimeout = 4000
            Map r = new JsonSlurper().parse(c.inputStream) as Map
            (r.data?.alerts as List ?: []).findAll { it.state in ['Alerting', 'firing'] }.collect {
                [host: app + (it.labels?.tier == 'web' ? '-web' : '-db'), state: it.labels?.severity == 'warning' ? 'Warning' : 'Critical',
                 subject: it.labels?.alertname ?: 'Alert', ms: utc(it.activeAt)]
            }
        } catch (Throwable ignored) { [] }
    }

    /** Waiting approvals of demo orders. */
    private List approvals(List apps) {
        List out = []
        try {
            (api.get('/api/approvals?max=50').approvals as List ?: []).findAll { it.status == 'requested' || it.status == '1 requested' || (it.status as String)?.contains('requested') }.each { ap ->
                Map full = api.get("/api/approvals/${ap.id}").approval as Map ?: ap
                (full.approvalItems as List ?: []).findAll { it.status == 'requested' }.each { i ->
                    String name = i.reference?.displayName ?: i.reference?.name ?: i.name
                    out << [itemId: i.id, name: name, by: full.requestBy ?: full.createdBy?.username ?: '', kind: full.requestType ?: 'Order',
                            ago: ago(utc(full.dateCreated))]
                }
            }
        } catch (Throwable t) { errors << 'Could not read approvals.' }
        out
    }

    private static List bars(List rows, String key) {
        Map sums = [:]
        rows.each { r -> String k = (r[key] ?: 'Not set') as String; sums[k] = ((sums[k] ?: 0) as BigDecimal) + ((r.costValue ?: 0) as BigDecimal) }
        BigDecimal max = (sums.values().max() ?: 1) as BigDecimal
        if (max == 0) max = 1
        sums.collect { k, val -> [name: k, cost: money(val as BigDecimal), pct: ((val as BigDecimal) * 100 / max).setScale(0, BigDecimal.ROUND_HALF_UP)] }
            .sort { a, b -> (b.pct as BigDecimal) <=> (a.pct as BigDecimal) }
    }

    /** HVM hosts and the shared datastore. */
    private Map hvm() {
        try {
            List hosts = (api.get('/api/servers?max=50').servers as List ?: []).findAll { it.computeServerType?.code == 'mvmHost' }.collect { s ->
                long maxM = (s.maxMemory ?: 0) as long, used = (s.stats?.usedMemory ?: 0) as long
                int memPct = maxM ? (used * 100 / maxM) as int : 0
                [name: s.name, status: s.powerState ?: s.status, ok: (s.powerState ?: s.status) in ['on', 'provisioned', 'ok'],
                 cpu: ((s.stats?.cpuUsage ?: 0) as BigDecimal).setScale(0, BigDecimal.ROUND_HALF_UP), memPct: memPct,
                 mem: "${(used / 1073741824).round(1)} of ${(maxM / 1073741824).round(1)} GB".toString()]
            }
            Map env = new Setup(api: api).hvm()
            Map ds = null
            if (env.cluster && env.datastore) {
                Map d = (api.get("/api/clusters/${env.cluster.id}/datastores").datastores as List)?.find { it.id == env.datastore.id } as Map
                if (d?.storageSize) {
                    long total = d.storageSize as long, free = (d.freeSpace ?: 0) as long
                    ds = [name: d.name, pct: ((total - free) * 100 / total) as int, text: "${((total - free) / 1073741824) as long} of ${(total / 1073741824) as long} GB used"]
                }
            }
            [cluster: env.cluster?.name, hosts: hosts, datastore: ds, network: env.network?.name]
        } catch (Throwable t) {
            [hosts: []]
        }
    }

    private List activity(List rows) {
        Set names = rows.collectMany { r -> [r.name] + r.tiers.collect { it.name } } as Set
        try {
            (api.get('/api/activity?max=100').activity as List ?: []).findAll { names.contains(it.name) }
                .take(40).collect { a ->
                    Long t = utc(a.ts)
                    [ms: t, when: t ? new Date(t).format('HH:mm') : '', cls: a.success == false ? 'crit' : 'ok', text: "${a.activityType}: ${a.name}".toString(), detail: a.message ?: '']
                }
        } catch (Throwable ignored) { [] }
    }
}
