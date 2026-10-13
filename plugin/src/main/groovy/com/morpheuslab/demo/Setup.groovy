package com.morpheuslab.demo

import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

/** Makes, checks and removes everything the demo needs, through Morpheus' own REST API as the signed-in user. */
@Slf4j
class Setup {
    static final String GROUP = 'Demo Apps'
    static final String ITEM = 'Demo Two Tier App'
    static final String OUTAGE = 'Demo Server Issue'
    static final List LABELS = ['morpheus-demo']
    static final String LAYOUT_CODE = 'ubuntu-24.04-qcow'

    SelfApi api
    Map settings = [:]
    List<Map> lines = []

    // name, price type, price per month
    static final List PRICES = [['Demo CPU', 'cores', 8.0], ['Demo Memory', 'memory', 4.0], ['Demo Storage', 'storage', 0.10]]
    // name, cores, memory GB, disk GB
    static final List PLANS = [['Demo Small', 1, 1, 20], ['Demo Medium', 1, 2, 25], ['Demo Large', 2, 4, 30]]
    static final List LISTS = [
        ['Demo Environments', [['Dev', 'Dev'], ['Test', 'Test'], ['Production', 'Production']]],
        ['Demo Monitoring', [['None', 'none'], ['OpsRamp', 'opsramp'], ['Grafana', 'grafana']]],
        // values are the built-in execute schedules: 1 daily at midnight, 2 weekly on Sunday
        ['Demo Backups', [['No backup', '0'], ['Every day at midnight', '1'], ['Every Sunday at midnight', '2']]],
        ['Demo Issue Actions', [['CPU spike', 'cpu'], ['Memory pressure', 'memory'], ['Stop the database', 'stop'], ['Fix everything', 'fix']]]]
    static final String SAFE = '^[A-Za-z0-9 ._@-]{1,60}$'
    // field, type, label, extra
    static final List INPUTS = [
        ['demoAppName', 'text', 'App name', [required: true, placeHolder: 'shop-dev', verifyPattern: '^[a-z][a-z0-9-]{2,20}$',
                                             helpBlock: 'Small letters, numbers and dashes. Used for the VM names.']],
        ['demoOwner', 'text', 'Your name', [required: true, verifyPattern: SAFE]],
        ['demoEmail', 'text', 'Email', [required: true, verifyPattern: '^[^@ ]+@[^@ ]+\\.[a-z]{2,}$']],
        ['demoEnv', 'radio', 'Environment', [required: true, list: 'Demo Environments', defaultValue: 'Dev']],
        ['demoProdReason', 'textarea', 'Why production?', [visibleOnCode: 'demoEnv:Production', requireOnCode: 'demoEnv:Production', helpBlock: 'Shown only for Production.']],
        ['demoSize', 'select', 'Size', [required: true, list: 'Demo Sizes']],
        ['demoNetwork', 'select', 'Network', [required: true, list: 'Demo Networks', helpBlock: 'Read live from the Morpheus API.']],
        ['demoPassword', 'password', 'Server password', [required: true, verifyPattern: '^[A-Za-z0-9!@#%^*_+=.,:-]{8,32}$',
                                                         helpBlock: 'For user demo on both VMs. 8 to 32 characters.']],
        ['demoMonitoring', 'radio', 'Monitoring', [required: true, list: 'Demo Monitoring', defaultValue: 'none']],
        ['demoBackup', 'select', 'Backup', [required: true, list: 'Demo Backups', defaultValue: '0']],
        ['demoDays', 'number', 'Keep for (days)', [required: true, defaultValue: '7', helpBlock: 'The app is removed after this.']],
        ['demoCostOwner', 'text', 'Cost owner', [required: true, verifyPattern: SAFE, exportMeta: true]],
        ['demoCostApprover', 'text', 'Cost approver', [required: true, verifyPattern: SAFE, exportMeta: true]],
        ['demoCostId', 'text', 'Cost ID', [required: true, placeHolder: 'CC-1001', verifyPattern: '^[A-Za-z0-9-]{2,20}$', exportMeta: true]],
        ['demoNotes', 'textarea', 'Notes', [:]],
        ['demoAgree', 'checkbox', 'I agree to the monthly cost', [required: true]]]
    static final List TASKS = [['Demo Create User', 'user.sh'], ['Demo Install MariaDB', 'db.sh'], ['Demo Install Web App', 'web.sh'],
                               ['Demo Grafana Exporters', 'grafana-db.sh'], ['Demo Grafana Server', 'grafana-web.sh'], ['Demo OpsRamp Agent', 'opsramp.sh'],
                               [OUTAGE, 'issue.sh']]
    static final List WORKFLOWS = [
        ['Demo Database Tier', ['Demo Create User', 'Demo Install MariaDB', 'Demo Grafana Exporters', 'Demo OpsRamp Agent']],
        ['Demo Web Tier', ['Demo Create User', 'Demo Install Web App', 'Demo Grafana Server', 'Demo OpsRamp Agent']]]
    static final List CYPHER = [['demo-opsramp-key', 'opsrampAgentKey'], ['demo-opsramp-secret', 'opsrampAgentSecret'],
                                ['demo-opsramp-server', 'opsrampServer'], ['demo-opsramp-tenant', 'opsrampTenant'], ['demo-opsramp-url', 'opsrampUrl']]
    // the Morpheus API list gives name and id only
    static final String NETWORKS_JS = "for (var i = 0; i < data.length; i++) { results.push({name: data[i].name, value: data[i].id}); }"
    static final String CONTENT = '''**What you get**

- Two Ubuntu 24.04 VMs on HPE VM Essentials (HVM): a web tier with Apache and PHP, and a database tier with MariaDB
- A live web page that shows how the two tiers are connected, who is using the app and a trace of every request
- Monitoring with OpsRamp or Grafana, picked by you
- Backups on a schedule, cost tags, and an end date

**How it works**

1. Fill in the form and order.
2. A Morpheus admin approves the order.
3. Morpheus builds the database VM, then the web VM, and runs the install workflows.
4. Open **Provisioning > Apps**, click your app and open the web VM's IP in a browser.'''
    static final String OUTAGE_CONTENT = '''**What it does**

- Makes a problem on a demo VM you pick: a CPU spike, memory pressure, or a stopped database
- **Fix everything** puts the VM back to normal
- Shows a Morpheus day 2 operation: a workflow run on an existing VM

**What you see**

- OpsRamp or Grafana raises the alert: High CPU, High memory or Database down
- A stopped database turns the app page red
- After the fix the alerts clear

Stop the database only on the VM whose name ends with **-db**. CPU and memory problems stop by themselves after 30 minutes.'''

    private void ok(String text) { lines << [ok: true, text: text] }
    private void bad(String text) { lines << [ok: false, text: text] }

    private static boolean good(Map r) { (r._status as Integer) < 300 && r.success != false }

    private Map find(String path, String key, String name) {
        // Remove only deactivates prices, so look at those too
        String inactive = path == '/api/prices' ? '&includeInactive=true' : ''
        Map r = api.get("${path}?max=500&phrase=${URLEncoder.encode(name, 'UTF-8')}${inactive}")
        (r[key] as List)?.find { it.name == name } as Map
    }

    /** Creates the object, or updates it when one with that name exists. */
    private Map ensure(String path, String key, String one, Map body) {
        Map have = find(path, key, body.name as String)
        Map r = have ? api.put("${path}/${have.id}", [(one): body]) : api.post(path, [(one): body])
        if (!good(r)) {
            bad("${body.name}: ${r.msg ?: r.errors ?: r._status}")
            return have
        }
        Object id = have?.id ?: (r[one] as Map)?.id ?: r.id
        ok("${body.name} ${have ? 'updated' : 'made'}")
        (api.get("${path}/${id}")[one] ?: [id: id, name: body.name]) as Map
    }

    /** The HVM cluster and what the blueprint needs from it. */
    Map hvm() {
        Map env = [:]
        List all = api.get('/api/clusters?max=100').clusters as List ?: []
        // the status flips to syncing during a refresh, so only skip clusters that are broken or going away
        Map c = all.find { (it.type?.code == 'mvm-cluster' || it.type?.name == 'HVM') && !(it.status in ['failed', 'deleting', 'removing']) } as Map
        if (!c) {
            log.warn("Morpheus Demo: no HVM cluster in ${all.collect { "${it.name}/${it.type?.name}/${it.status}" }}")
            return env
        }
        env.cluster = [id: c.id, name: c.name]
        env.cloud = [id: c.zone?.id, name: c.zone?.name]
        Object itId = (api.get('/api/instance-types?max=200&phrase=Ubuntu').instanceTypes as List)?.find { it.code == 'ubuntu' }?.id
        Map it = itId ? api.get("/api/instance-types/${itId}").instanceType as Map : null
        Map layout = (it?.instanceTypeLayouts as List)?.find { it.code == LAYOUT_CODE } as Map
        if (layout) env.layout = [id: layout.id, code: layout.code, instanceTypeId: it.id]
        List ds = api.get("/api/clusters/${c.id}/datastores").datastores as List ?: []
        Map store = ds.find { it.defaultStore && it.online } ?: ds.find { it.online && it.name != 'morpheus-cloud-init' }
        if (store) env.datastore = [id: store.id, name: store.name, shared: store.defaultStore as boolean]
        Map net = (api.get('/api/networks?max=200').networks as List)?.find { it.type?.code == 'ovsPortGroup' && it.zone?.id == c.zone?.id } as Map
        if (net) env.network = [id: net.id, name: net.displayName ?: net.name]
        Object kvm = (api.get('/api/provision-types?max=50&code=kvm').provisionTypes as List)?.find { it.code == 'kvm' }
        env.kvmId = kvm?.id
        env
    }

    private Object pool(Map env, Object groupId) {
        Map r = api.get("/api/options/zonePools?zoneId=${env.cloud.id}&siteId=${groupId}&layoutId=${env.layout.id}&instanceTypeId=${env.layout.instanceTypeId}")
        (r.data as List)?.find { it.providerType == 'mvm' && it.value }?.value
    }

    private static String opt(String f) { "<%=customOptions.${f}%>" }

    private static List fields() { INPUTS.collect { it[0] } }

    /** Two tiers. With order=true the values come from the catalog form. */
    Map blueprint(Map env, String poolId, Map plan, Map workflows, boolean order) {
        Closure tier = { String short_, int idx, String wf, List linked ->
            Map cloud = [
                instance: [layout: [id: env.layout.id, code: env.layout.code], name: (order ? opt('demoAppName') : '${app.name}') + '-' + short_],
                plan: order ? [id: opt('demoSize')] : [id: plan.id, code: plan.code],
                // 20 GB: OpsRamp flags a disk with less than 6 GB free
                volumes: [[rootVolume: true, name: 'root', size: 20, storageType: 1, datastoreId: env.datastore.id]],
                networkInterfaces: [[network: [id: 'network-' + (order ? opt('demoNetwork') : env.network.id)]]],
                config: [resourcePoolId: poolId, createUser: true],
                taskSetId: workflows[wf].id]
            Map entry = [instance: [type: 'ubuntu'], groups: [(GROUP): [clouds: [(env.cloud.name): cloud]]]]
            if (order) {
                Map co = fields().collectEntries { [(it): opt(it)] }
                cloud.config.customOptions = co
                cloud.instance.expireDays = opt('demoDays')
                List tags = [['costOwner', 'demoCostOwner'], ['costApprover', 'demoCostApprover'], ['costId', 'demoCostId'],
                             ['environment', 'demoEnv'], ['owner', 'demoOwner']].collect { [name: it[0], value: opt(it[1])] }
                // tags are written through the metadata field
                cloud.metadata = tags
                cloud.instance.metadata = tags
                cloud.instance.labels = LABELS
                cloud.labels = LABELS
                cloud.backup = [createBackup: "<%=customOptions.demoBackup != '0'%>", jobAction: 'new',
                                jobName: opt('demoAppName') + '-' + short_ + ' backup', jobSchedule: opt('demoBackup'), jobRetentionCount: 3]
                // an order only reads the tier instance entry, not the cloud block
                ['plan', 'networkInterfaces', 'metadata', 'labels', 'backup', 'volumes'].each { entry[it] = cloud[it] }
                entry.instance = new LinkedHashMap(cloud.instance as Map) + [type: 'ubuntu']
                entry.config = new LinkedHashMap(cloud.config as Map)
            }
            [tierIndex: idx, bootSequence: idx, tier: [bootOrder: idx], linkedTiers: linked, instances: [entry]]
        }
        [name: ITEM, type: 'morpheus', tiers: [Database: tier('db', 0, 'Demo Database Tier', []),
                                                 Web: tier('web', 1, 'Demo Web Tier', ['Database'])]]
    }

    private String resource(String path) {
        InputStream s = Setup.classLoader.getResourceAsStream(path)
        s ? s.getText('UTF-8') : null
    }

    /** Makes or updates everything. Returns the log. */
    List<Map> run() {
        Map env = hvm()
        if (!env.cluster) { bad('No HVM cluster found. Add one under Infrastructure > Clusters first.'); return lines }
        if (!env.layout) { bad('The built-in Ubuntu 24.04 KVM layout was not found.'); return lines }
        if (!env.datastore) { bad('The HVM cluster has no online datastore.'); return lines }
        if (!env.network) { bad('No HVM network found. Add an HVM Standard Network first.'); return lines }
        ok("HVM cluster ${env.cluster.name}, datastore ${env.datastore.name}, network ${env.network.name}")

        List priceIds = PRICES.collect { p ->
            Map body = [name: p[0], code: "demo.${p[1]}", priceType: p[1], priceUnit: 'month', currency: 'USD', incurCharges: 'running', active: true,
                        price: p[2], cost: ((p[2] as BigDecimal) * 0.6).setScale(2, BigDecimal.ROUND_HALF_UP),
                        markupType: 'fixed', markup: ((p[2] as BigDecimal) * 0.4).setScale(2, BigDecimal.ROUND_HALF_UP)]
            if (p[1] == 'storage') body.volumeType = [id: 1]
            ensure('/api/prices', 'prices', 'price', body)?.id
        }.findAll()
        // a removed (deactivated) price set can not come back, so a new one gets its own code
        Map priceSet = ensure('/api/price-sets', 'priceSets', 'priceSet',
            [name: 'Demo HVM Prices', code: find('/api/price-sets', 'priceSets', 'Demo HVM Prices')?.code ?: "demo.hvm.${System.currentTimeMillis()}".toString(),
             type: 'component', priceUnit: 'month', prices: priceIds.collect { [id: it] }])
        Map plans = PLANS.withIndex().collectEntries { p, i ->
            [(p[0]): ensure('/api/service-plans', 'servicePlans', 'servicePlan', [
                name: p[0], code: 'demo-' + (p[0] as String).split(' ')[1].toLowerCase(), provisionType: [id: env.kvmId],
                description: "${p[1]} vCPU, ${p[2]} GB memory, ${p[3]} GB disk".toString(), maxCores: p[1], coresPerSocket: 1,
                maxMemory: (p[2] as Long) * 1024L ** 3, maxStorage: (p[3] as Long) * 1024L ** 3, sortOrder: i, active: true,
                editable: true, priceSets: priceSet ? [[id: priceSet.id]] : [], config: [:]])]
        }

        Map lists = LISTS.collectEntries { l ->
            [(l[0]): ensure('/api/library/option-type-lists', 'optionTypeLists', 'optionTypeList', [name: l[0], type: 'manual', labels: LABELS,
                initialDataset: JsonOutput.toJson((l[1] as List).collect { [name: it[0], value: it[1]] })])]
        }
        // the size values are the plan ids, so the order can pick the plan
        lists['Demo Sizes'] = ensure('/api/library/option-type-lists', 'optionTypeLists', 'optionTypeList', [name: 'Demo Sizes', type: 'manual', labels: LABELS,
            initialDataset: JsonOutput.toJson(PLANS.findAll { plans[it[0]] }.collect { [name: "${(it[0] as String).replace('Demo ', '')}: ${it[1]} vCPU, ${it[2]} GB".toString(), value: plans[it[0]].id as String] })])
        // Morpheus API list: read live with the ordering user's own rights, no token needed
        lists['Demo Networks'] = ensure('/api/library/option-type-lists', 'optionTypeLists', 'optionTypeList', [
            name: 'Demo Networks', type: 'api', apiType: 'networks', labels: LABELS, translationScript: NETWORKS_JS])
        List inputs = INPUTS.withIndex().collect { p, i ->
            Map extra = new LinkedHashMap(p[3] as Map)
            Map body = [name: "Demo ${p[2]}".toString(), fieldName: p[0], type: p[1], fieldLabel: p[2], required: extra.remove('required') ?: false,
                        displayOrder: i, labels: LABELS]
            if (extra.list) body.optionList = [id: lists[extra.remove('list')]?.id]
            if (p[0] == 'demoSize' && plans['Demo Small']) body.defaultValue = plans['Demo Small'].id as String
            if (p[0] == 'demoNetwork') body.defaultValue = env.network.id as String
            body.putAll(extra)
            ensure('/api/library/option-types', 'optionTypes', 'optionType', body)
        }.findAll()

        int saved = 0
        CYPHER.each { c ->
            String v = settings[c[1]] as String
            if (v) { if (good(api.post("/api/cypher/secret/${c[0]}", [value: v]))) saved++ }
        }
        saved == CYPHER.size() ? ok('OpsRamp settings saved in Cypher') : ok("OpsRamp settings in Cypher: ${saved} of ${CYPHER.size()} (fill in the plugin settings to use OpsRamp)")

        Map group = ensure('/api/groups', 'groups', 'group', [name: GROUP, code: 'demo-apps', location: 'Lab', labels: LABELS])
        if (group) api.put("/api/groups/${group.id}/update-zones", [group: [zones: [[id: env.cloud.id]]]])
        policies(group)

        String sizes = PLANS.findAll { plans[it[0]] }.collect { "  ${plans[it[0]].id}) SIZE='${(it[0] as String).replace('Demo ', '')}' ;;" }.join('\n')
        Map tasks = TASKS.collectEntries { t ->
            [(t[0]): ensure('/api/tasks', 'tasks', 'task', [name: t[0], taskType: [code: 'script'], executeTarget: 'resource', labels: LABELS,
                file: [sourceType: 'local', content: resource("demo/tasks/${t[1]}").replace('@@SIZES@@', sizes)], taskOptions: ['shell.sudo': 'on']])]
        }
        Map workflows = WORKFLOWS.collectEntries { w ->
            [(w[0]): ensure('/api/task-sets', 'taskSets', 'taskSet', [name: w[0], type: 'provision', labels: LABELS,
                tasks: (w[1] as List).withIndex().collect { n, i -> [taskId: tasks[n]?.id, taskPhase: 'postProvision', taskOrder: i] }])]
        }
        String poolId = group ? pool(env, group.id) : null
        if (!poolId) { bad('The HVM resource pool was not found for the Demo Apps group.'); return lines }
        Map bp = ensure('/api/blueprints', 'blueprints', 'blueprint', [name: ITEM, type: 'morpheus', labels: LABELS,
            description: 'Web tier (Apache, PHP) and database tier (MariaDB) on HVM.',
            config: blueprint(env, poolId, plans['Demo Small'] as Map, workflows, false)])

        Map spec = blueprint(env, poolId, null, workflows, true)
        Map co = fields().collectEntries { [(it): opt(it)] }
        spec.putAll([name: opt('demoAppName'), description: "Ordered by ${opt('demoOwner')} for ${opt('demoEnv')}".toString(),
                     group: [id: group.id, name: GROUP], defaultCloud: env.cloud, environment: opt('demoEnv'), labels: LABELS,
                     customOptions: co, config: [customOptions: co]])
        Map item = ensure('/api/catalog-item-types', 'catalogItemTypes', 'catalogItemType', [
            name: ITEM, type: 'blueprint', blueprint: [id: bp?.id], appSpec: JsonOutput.prettyPrint(JsonOutput.toJson(spec)),
            optionTypes: inputs*.id, enabled: true, featured: true, visibility: 'public', labels: LABELS, category: 'Demo',
            description: 'Web and database VMs on HVM, with monitoring and backups.', content: CONTENT])
        issue(lists, tasks)
        dashboard(true)
        logo(item, 'two-tier-app.svg')
        lines
    }

    /** The day 2 item: make a problem on a demo VM, or fix it. */
    private void issue(Map lists, Map tasks) {
        Map input = ensure('/api/library/option-types', 'optionTypes', 'optionType', [name: 'Demo Action', fieldName: 'demoAction', type: 'radio',
            fieldLabel: 'Action', required: true, defaultValue: 'cpu', optionList: [id: lists['Demo Issue Actions']?.id], labels: LABELS])
        Map wf = ensure('/api/task-sets', 'taskSets', 'taskSet', [name: OUTAGE, type: 'operation', labels: LABELS,
            tasks: [[taskId: tasks[OUTAGE]?.id, taskPhase: 'operation', taskOrder: 0]]])
        Map item = ensure('/api/catalog-item-types', 'catalogItemTypes', 'catalogItemType', [
            name: OUTAGE, type: 'workflow', workflow: [id: wf?.id], context: 'instance', optionTypes: [input?.id].findAll(),
            enabled: true, featured: false, visibility: 'public', labels: LABELS, category: 'Demo',
            description: 'Make a CPU, memory or database problem on a demo VM, then fix it.', content: OUTAGE_CONTENT])
        logo(item, 'server-issue.svg')
    }

    private void logo(Map item, String file) {
        byte[] bytes = item ? Setup.classLoader.getResourceAsStream("demo/${file}")?.bytes : null
        if (!bytes) return
        Map r = api.upload('PUT', "/api/catalog-item-types/${item.id}/update-logo", 'catalogItemType.logo', file, bytes, 'image/svg+xml')
        (r.status as Integer) < 300 ? ok("Logo set for ${item.name}") : bad("Logo for ${item.name}: ${r.status}")
    }

    /** Adds or takes the Demo Apps dashboard to Operations > Dashboard, keeping the others. */
    private void dashboard(boolean show) {
        List cur = ((api.get('/api/appliance-settings').applianceSettings?.dashboardsToDisplay ?: '') as String).split(',')*.trim().findAll()
        List next = show ? ([DemoPlugin.CODE] + cur).unique() : cur - [DemoPlugin.CODE]
        if (next == cur) { if (show) ok('Demo Apps dashboard shown'); return }
        Map r = api.put('/api/appliance-settings', [applianceSettings: [dashboardsToDisplay: next.join(',')]])
        good(r) ? ok(show ? 'Demo Apps dashboard shown on Operations > Dashboard' : 'Demo Apps dashboard taken off') : bad("Dashboard setting: ${r.msg ?: r._status}")
    }

    private void policies(Map group) {
        if (!group) return
        Map have = (api.get('/api/policies?max=200&phrase=Demo').policies as List ?: []).collectEntries { [(it.name): it] }
        // -100 is Morpheus' own approval (Operations > Approvals)
        [['Demo Approval', 'provisionApproval', [accountIntegrationId: -100]],
         ['Demo Expiration', 'lifecycle', [lifecycleType: 'user', lifecycleAge: 30, lifecycleRenewal: 7, lifecycleNotify: 1,
                                           lifecycleAutoRenew: false, lifecycleExtensionsBeforeApproval: 0, accountIntegrationId: -100]]].each { p ->
            Map body = [name: p[0], description: 'Morpheus demo', enabled: true, policyType: [code: p[1]], refType: 'ComputeSite',
                        refId: group.id, site: [id: group.id], config: p[2]]
            Map r = have[p[0]] ? api.put("/api/policies/${have[p[0]].id}", [policy: body]) : api.post('/api/policies', [policy: body])
            good(r) ? ok("${p[0]} ${have[p[0]] ? 'updated' : 'made'}") : bad("${p[0]}: ${r.msg ?: r._status}")
        }
    }

    /** What exists now, for the Check button and the page. */
    List<Map> check() {
        Map env = hvm()
        lines << [ok: env.cluster != null, text: env.cluster ? "HVM cluster ${env.cluster.name}" : 'No HVM cluster']
        lines << [ok: env.datastore != null, text: env.datastore ? "Datastore ${env.datastore.name}${env.datastore.shared ? ' (image target)' : ''}" : 'No datastore']
        lines << [ok: env.network != null, text: env.network ? "Network ${env.network.name}" : 'No HVM network']
        lines << [ok: env.layout != null, text: env.layout ? 'Ubuntu 24.04 layout' : 'Ubuntu 24.04 layout missing']
        objects().each { o -> lines << [ok: o.id != null, text: o.id ? "${o.name}" : "${o.name} missing"] }
        int set = CYPHER.count { settings[it[1]] } as int
        if (set < CYPHER.size()) {
            lines << [ok: false, text: "OpsRamp settings: ${set} of ${CYPHER.size()} filled in"]
        } else {
            // the VMs read the agent keys from Cypher, which only Set up fills
            boolean same = CYPHER.every { (api.get("/api/cypher/secret/${it[0]}").data as Map)?.value == settings[it[1]] }
            lines << [ok: same, text: same ? 'OpsRamp settings filled in' : 'OpsRamp settings changed: press Set up to use them']
        }
        lines
    }

    /** Every object the demo makes, in the order Remove deletes them. */
    List<Map> objects() {
        List spec = [['/api/catalog-item-types', 'catalogItemTypes', 'Catalog item', [ITEM, OUTAGE]], ['/api/blueprints', 'blueprints', 'Blueprint', [ITEM]],
                     ['/api/task-sets', 'taskSets', 'Workflow', WORKFLOWS.collect { it[0] } + [OUTAGE]], ['/api/tasks', 'tasks', 'Task', TASKS.collect { it[0] }],
                     ['/api/library/option-types', 'optionTypes', 'Input', INPUTS.collect { "Demo ${it[2]}" } + ['Demo Action']],
                     ['/api/library/option-type-lists', 'optionTypeLists', 'Option list', LISTS.collect { it[0] } + ['Demo Sizes', 'Demo Networks']],
                     ['/api/policies', 'policies', 'Policy', ['Demo Approval', 'Demo Expiration']],
                     ['/api/service-plans', 'servicePlans', 'Plan', PLANS.collect { it[0] }], ['/api/price-sets', 'priceSets', 'Price set', ['Demo HVM Prices']],
                     ['/api/prices', 'prices', 'Price', PRICES.collect { it[0] }], ['/api/groups', 'groups', 'Group', [GROUP]]]
        spec.collectMany { s ->
            Map found = (api.get("${s[0]}?max=500&phrase=Demo")[s[1]] as List ?: []).collectEntries { [(it.name): it.id] }
            (s[3] as List).collect { n -> [path: s[0], name: "${s[2]}: ${n}".toString(), id: found[n]] }
        }
    }

    /** Deletes what the demo made. Refuses while demo apps still exist. */
    List<Map> remove() {
        Map g = find('/api/groups', 'groups', GROUP)
        int apps = g ? ((api.get("/api/apps?max=200").apps as List)?.count { it.group?.id == g.id } ?: 0) : 0
        if (apps) { bad("Delete the ${apps} demo app${apps == 1 ? '' : 's'} first (Provisioning > Apps or the Demo Apps dashboard)."); return lines }
        List vms = g ? (api.get('/api/instances?max=200').instances as List ?: []).findAll { it.group?.id == g.id }*.name : []
        if (vms) { bad("Delete these VMs in the Demo Apps group first (Provisioning > Instances): ${vms.join(', ')}."); return lines }
        objects().findAll { it.id }.each { o ->
            // prices and price sets can not be deleted, only deactivated
            Map r = o.path in ['/api/prices', '/api/price-sets'] ? api.put("${o.path}/${o.id}/deactivate", [:]) : api.delete("${o.path}/${o.id}")
            good(r) ? ok("${o.name} removed") : bad("${o.name}: ${r.msg ?: r._status}")
        }
        dashboard(false)
        CYPHER.each { api.delete("/api/cypher/secret/${it[0]}") }
        ok('OpsRamp settings removed from Cypher')
        lines
    }
}
