package com.morpheuslab.demo

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.dashboard.AbstractDashboardItemTypeProvider
import com.morpheusdata.model.DashboardItem
import com.morpheusdata.model.DashboardItemType
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/** The dashboard's one wide item: everything is drawn here, and redrawn every 30 s by the page script. */
@Slf4j
class DashItemProvider extends AbstractDashboardItemTypeProvider {
    static final String CODE = 'morpheus-demo-main'
    DemoPlugin plugin
    MorpheusContext morpheusContext

    DashItemProvider(DemoPlugin plugin, MorpheusContext context) { this.plugin = plugin; this.morpheusContext = context }

    MorpheusContext getMorpheus() { morpheusContext }
    Plugin getPlugin() { plugin }
    String getCode() { CODE }
    String getName() { 'Demo Apps' }

    @Override
    DashboardItemType getDashboardItemType() {
        DashboardItemType t = new DashboardItemType()
        t.name = getName()
        t.code = CODE
        t.category = 'morpheus-demo'
        t.title = 'Demo Apps'
        t.description = 'Orders, approvals, health and cost of the demo apps'
        t.uiSize = 'xl'
        t.templatePath = 'hbs/dash-main'
        t.scriptPath = 'morpheus-demo.js'
        t
    }

    @Override
    HTMLResponse renderDashboardItem(DashboardItem item, Map<String, Object> opts) {
        ViewModel<Map> model = new ViewModel<>()
        model.object = view(plugin)
        getRenderer().renderTemplate('hbs/dash-main', model)
    }

    /** The view model, also used by the refresh route. */
    static Map view(DemoPlugin plugin) {
        Map v
        try {
            v = new Collector(api: SelfApi.of(Req.current()), settings: plugin.settingsMap()).collect()
        } catch (Throwable t) {
            log.warn("Morpheus Demo: collect failed", t)
            v = [errors: ["Could not build the dashboard: ${t.message?.take(160)}".toString()]]
        }
        v.csrf = Csrf.token()
        v
    }
}
