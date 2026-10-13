package com.morpheuslab.demo

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.dashboard.AbstractDashboardProvider
import com.morpheusdata.model.Dashboard
import com.morpheusdata.model.DashboardItem
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/** The "Demo Apps" dashboard on Operations > Dashboard. */
@Slf4j
class DashboardProvider extends AbstractDashboardProvider {
    Plugin plugin
    MorpheusContext morpheusContext

    DashboardProvider(Plugin plugin, MorpheusContext context) { this.plugin = plugin; this.morpheusContext = context }

    MorpheusContext getMorpheus() { morpheusContext }
    Plugin getPlugin() { plugin }
    String getCode() { 'morpheus-demo' }
    String getName() { 'Demo Apps' }

    @Override
    Dashboard getDashboard() {
        Dashboard d = new Dashboard()
        d.name = getName()
        d.code = getCode()
        d.dashboardId = 'morpheus-demo'
        d.category = 'morpheus-demo'
        d.title = 'Demo Apps'
        d.description = 'The Morpheus demo apps: orders, approvals, health and cost'
        // must be true, or Morpheus can not select this dashboard and never renders it
        d.defaultDashboard = true
        d.enabled = true
        d.sourceType = 'system'
        d.templatePath = 'hbs/dash-shell'
        // must not be empty, or Morpheus unloads the plugin
        d.scriptPath = 'morpheus-demo.js'
        DashboardItem item = null
        try {
            def type = morpheus.getDashboard().getDashboardItemType(DashItemProvider.CODE).blockingGet()
            if (type) item = new DashboardItem(type: type, itemRow: 0, itemColumn: 0, itemGroup: 'morpheus-demo', groupRow: 0)
        } catch (Throwable t) {
            log.warn("Morpheus Demo: dashboard item not ready yet: ${t}")
        }
        d.dashboardItems = item ? [item] : []
        d
    }

    @Override
    HTMLResponse renderDashboard(Dashboard dashboard, Map<String, Object> opts) {
        ViewModel<Dashboard> model = new ViewModel<>()
        model.object = dashboard
        model.opts = opts
        getRenderer().renderTemplate(dashboard.templatePath, model)
    }
}
