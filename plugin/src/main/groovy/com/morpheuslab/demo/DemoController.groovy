package com.morpheuslab.demo

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/** Set up, Check and Remove, plus the dashboard refresh, approvals and app delete. */
@Slf4j
class DemoController implements PluginController {
    DemoPlugin plugin
    MorpheusContext morpheus

    DemoController(DemoPlugin plugin, MorpheusContext morpheus) { this.plugin = plugin; this.morpheus = morpheus }

    String getCode() { 'morpheus-demo-controller' }
    String getName() { 'Morpheus Demo Controller' }
    MorpheusContext getMorpheus() { morpheus }
    Plugin getPlugin() { plugin }

    List<Route> getRoutes() {
        Permission read = Permission.build(DemoPlugin.CODE, 'read')
        Permission full = Permission.build(DemoPlugin.CODE, 'full')
        [Route.build('/morpheus-demo/setup', 'setup', full),
         Route.build('/morpheus-demo/check', 'check', read),
         Route.build('/morpheus-demo/remove', 'remove', full),
         Route.build('/morpheus-demo/content', 'content', read),
         Route.build('/morpheus-demo/approval', 'approval', read),
         Route.build('/morpheus-demo/delete-app', 'deleteApp', full)]
    }

    private Setup setupFor(def req) { new Setup(api: SelfApi.of(req), settings: plugin.settingsMap()) }

    private static boolean post(ViewModel model) { 'POST'.equalsIgnoreCase(model.request?.method as String) }

    private def run(ViewModel model, String what, Closure<List> work) {
        if (!post(model)) return JsonResponse.of([ok: false, log: [[ok: false, text: 'Use the button on the page.']]])
        try {
            List lines = work()
            log.info("Morpheus Demo: ${model.user?.username} ran ${what}: ${lines.count { !it.ok }} problems")
            JsonResponse.of([ok: lines.every { it.ok }, log: lines])
        } catch (Throwable t) {
            log.warn("Morpheus Demo: ${what} failed", t)
            JsonResponse.of([ok: false, log: [[ok: false, text: "${what} stopped: ${t.message?.take(160)}".toString()]]])
        }
    }

    def setup(ViewModel<Map> model) { run(model, 'Set up') { setupFor(model.request).run() } }

    def check(ViewModel<Map> model) {
        try {
            List lines = setupFor(model.request).check()
            JsonResponse.of([ok: lines.every { it.ok }, log: lines])
        } catch (Throwable t) {
            JsonResponse.of([ok: false, log: [[ok: false, text: "Check stopped: ${t.message?.take(160)}".toString()]]])
        }
    }

    def remove(ViewModel<Map> model) { run(model, 'Remove') { setupFor(model.request).remove() } }

    /** The dashboard content again, for the 30 s refresh. */
    def content(ViewModel<Map> model) {
        ViewModel<Map> m = new ViewModel<>()
        m.object = DashItemProvider.view(plugin)
        plugin.getRenderer().renderTemplate('hbs/dash-main', m)
    }

    /** Approves or rejects one approval item with the user's own rights. */
    def approval(ViewModel<Map> model) {
        if (!post(model)) return JsonResponse.of([ok: false, text: 'Use the button on the page.'])
        String id = model.request.getParameter('item') as String, action = model.request.getParameter('do') as String
        if (!(id?.isLong() && action in ['approve', 'deny'])) return JsonResponse.of([ok: false, text: 'Unknown request.'])
        Map r = SelfApi.of(model.request).put("/api/approval-items/${id}/${action}", [:])
        boolean ok = (r._status as Integer) < 300 && r.success != false
        log.info("Morpheus Demo: ${model.user?.username} ${action} approval item ${id}: ${ok ? 'ok' : r._status}")
        JsonResponse.of([ok: ok, text: ok ? (action == 'approve' ? 'Approved. Morpheus is building the app.' : 'Rejected.') :
            'That did not work. You may not have permission to approve.'])
    }

    /** Deletes one demo app and its VMs. Only apps in the Demo Apps group. */
    def deleteApp(ViewModel<Map> model) {
        if (!post(model)) return JsonResponse.of([ok: false, text: 'Use the button on the page.'])
        String id = model.request.getParameter('app') as String
        SelfApi api = SelfApi.of(model.request)
        Map app = id?.isLong() ? api.get("/api/apps/${id}").app as Map : null
        if (app?.group?.name != Setup.GROUP) return JsonResponse.of([ok: false, text: 'Only demo apps can be deleted here.'])
        HealthWatch.GONE << (app.name as String)
        Map r = api.delete("/api/apps/${id}?removeInstances=on&preserveVolumes=off")
        boolean ok = (r._status as Integer) < 300 && r.success != false
        log.info("Morpheus Demo: ${model.user?.username} deleted app ${app.name}: ${ok ? 'ok' : r._status}")
        if (ok) {
            try { OpsRamp.of(plugin.settingsMap())?.dropApp(app.name as String) } catch (Throwable t) { log.warn("Morpheus Demo: OpsRamp clean up of ${app.name}: ${t.message}") }
        }
        JsonResponse.of([ok: ok, text: ok ? "Deleting ${app.name} and its VMs.".toString() : "Could not delete: ${r.msg ?: r._status}".toString()])
    }
}
