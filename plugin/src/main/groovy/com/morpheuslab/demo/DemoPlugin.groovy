package com.morpheuslab.demo

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HandlebarsRenderer
import groovy.json.JsonSlurper

/** Morpheus Demo: sets up a ready to show demo on HVM and adds the Demo Apps dashboard. */
class DemoPlugin extends Plugin {

    static final String CODE = 'morpheus-demo'

    @Override
    String getCode() { CODE }

    @Override
    void initialize() {
        setName('Morpheus Demo')
        setDescription('Demo catalog, blueprint, prices and plans on HVM, with a two tier app and a dashboard')
        // a plugin with routes needs its own renderer
        HandlebarsRenderer r = new HandlebarsRenderer('renderer', getClassLoader())
        r.registerAssetHelper(getName())
        r.registerNonceHelper(morpheus.getWebRequest())
        r.registerI18nHelper(this, morpheus)
        setRenderer(r)
        // read: see the dashboard; full: also Set up and Remove
        Permission p = Permission.build('Morpheus Demo', CODE, [Permission.AccessType.none, Permission.AccessType.read, Permission.AccessType.full])
        p.subCategory = 'Morpheus Demo'
        setPermissions([p])
        registerProvider(new DemoIntegrationProvider(this, morpheus))
        registerProvider(new PageScript(this, morpheus))
        registerProvider(new DashItemProvider(this, morpheus))
        registerProvider(new DashboardProvider(this, morpheus))
        controllers.add(new DemoController(this, morpheus))
        watch = new HealthWatch(plugin: this)
        watch.start()
    }

    HealthWatch watch

    @Override
    List<OptionType> getSettings() {
        [setting('OpsRamp address', 'opsrampUrl', 'The OpsRamp page you log in to, for example https://example.app.opsramp.com', OptionType.InputType.TEXT, 0),
         setting('OpsRamp API server', 'opsrampServer', 'For example example.api.opsramp.com', OptionType.InputType.TEXT, 1),
         setting('OpsRamp tenant ID', 'opsrampTenant', 'The client unique ID', OptionType.InputType.TEXT, 2),
         setting('OpsRamp agent key', 'opsrampAgentKey', 'From the Linux agent install page', OptionType.InputType.PASSWORD, 3),
         setting('OpsRamp agent secret', 'opsrampAgentSecret', 'From the Linux agent install page', OptionType.InputType.PASSWORD, 4),
         setting('OpsRamp API key', 'opsrampApiKey', 'For monitoring templates and alerts', OptionType.InputType.PASSWORD, 5),
         setting('OpsRamp API secret', 'opsrampApiSecret', 'For monitoring templates and alerts', OptionType.InputType.PASSWORD, 6),
         setting('OpsRamp dashboard address', 'opsrampDashboardUrl', 'Optional. An OpsRamp dashboard for the Open OpsRamp button', OptionType.InputType.TEXT, 7)]
    }

    private OptionType setting(String label, String field, String help, OptionType.InputType type, int order) {
        new OptionType(name: label, code: "${CODE}.${field}", fieldName: field, fieldLabel: label, inputType: type,
            helpText: help, required: false, displayOrder: order)
    }

    /** Current settings as a map. */
    Map settingsMap() {
        try {
            String raw = morpheus.getSettings(this).blockingGet()
            return raw ? new JsonSlurper().parseText(raw) as Map : [:]
        } catch (Throwable ignored) {
            return [:]
        }
    }

    @Override
    void onDestroy() { watch?.stop() }
}
