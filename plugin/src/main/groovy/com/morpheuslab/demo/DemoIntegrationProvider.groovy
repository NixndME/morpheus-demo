package com.morpheuslab.demo

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.AbstractGenericIntegrationProvider
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel

/** Administration > Integrations > Morpheus Demo: Set up, Check and Remove. */
class DemoIntegrationProvider extends AbstractGenericIntegrationProvider {
    DemoPlugin plugin
    MorpheusContext morpheus

    DemoIntegrationProvider(DemoPlugin plugin, MorpheusContext morpheus) { this.plugin = plugin; this.morpheus = morpheus }

    String getCode() { 'morpheus-demo-integration' }
    String getName() { 'Morpheus Demo' }
    MorpheusContext getMorpheus() { morpheus }
    Plugin getPlugin() { plugin }

    String getCategory() { 'other' }
    List<OptionType> getOptionTypes() { [] }
    void refresh(AccountIntegration integration) { }
    Icon getIcon() { new Icon(path: 'morpheus-demo.svg', darkPath: 'morpheus-demo.svg') }

    HTMLResponse renderTemplate(AccountIntegration integration) {
        ViewModel<Map> m = new ViewModel<>()
        Map v = [:]
        try {
            Setup s = new Setup(api: SelfApi.of(Req.current()), settings: plugin.settingsMap())
            List lines = s.check()
            v.lab = lines.take(4)
            v.objects = lines.drop(4).dropRight(1)
            v.opsramp = lines.last()
            v.made = v.objects.count { it.ok }
            v.total = v.objects.size()
            v.ready = v.made == v.total
        } catch (Throwable t) {
            v.error = "Could not read the current state: ${t.message?.take(160)}".toString()
        }
        m.object = v
        plugin.renderer.renderTemplate('hbs/integration', m)
    }
}
