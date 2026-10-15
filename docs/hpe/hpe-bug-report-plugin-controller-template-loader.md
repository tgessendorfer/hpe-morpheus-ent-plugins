# Morpheus 9.1.0: a plugin with a controller and no own renderer cannot be uploaded

## Summary

Uploading a plugin that registers a `PluginController` and does not set a renderer of its own fails.
The REST upload answers `{"success":false,"msg":"Failed to parse plugin file"}`, and the plugin
that was installed before stays active. The cause is in the plugin API: `PluginManager` adds the
plugin's class loader to the shared `HandlebarsRenderer`, whose `DynamicTemplateLoader` keeps its
delegates in a fixed-size list (`Arrays.asList`). The `add` throws
`UnsupportedOperationException`.

The error text does not point at the cause. Nothing is wrong with the plugin file.

## Environment

| | |
|---|---|
| Appliance | HPE Morpheus Enterprise 9.1.0 (`morpheus-plugin-api-1.5.0.jar` in the appliance) |
| Plugin compiled against | `morpheus-plugin-api` 1.4.2 |
| Plugin | dashboard item type providers, a dashboard provider and one `PluginController` with one route |

## Steps to reproduce

1. Write a plugin whose `initialize()` adds a controller and does not call `setRenderer`:

   ```groovy
   @Override
   void initialize() {
       this.setName('Example')
       this.controllers.add(new ExampleController(this, morpheus))   // getRoutes() returns one Route
   }
   ```

2. Upload the jar: `POST /api/plugins/upload` with the jar as multipart part `file`, or through
   *Administration > Integrations > Plugins*.

## Expected

The plugin loads and its route is served below `/plugin/`.

## Actual

The upload answers `Failed to parse plugin file`. The appliance log shows:

```
Loading Plugin com.example.ExamplePlugin:1.0.0 from /var/opt/morpheus/morpheus-ui/plugins/example-1.0.0-all.jar
Unable to load plugin class from /var/opt/morpheus/morpheus-ui/plugins/example-1.0.0-all.jar
java.lang.UnsupportedOperationException
	at java.base/java.util.AbstractList.add(Unknown Source)
	at java.base/java.util.AbstractList.add(Unknown Source)
	at com.morpheusdata.views.DynamicTemplateLoader.addTemplateLoader(DynamicTemplateLoader.java:124)
	at com.morpheusdata.views.HandlebarsRenderer.addTemplateLoader(HandlebarsRenderer.java:70)
	at com.morpheusdata.core.PluginManager.registerPlugin(PluginManager.java:118)
	at com.morpheusdata.core.PluginManager.registerPlugin(PluginManager.java:156)
	at com.morpheus.plugin.PluginManagerService.saveAndRegisterPlugin(PluginManagerService.groovy:1097)
	at com.morpheus.api.PluginsController.upload(PluginsController.groovy:58)
```

The rejected jar stays in `/var/opt/morpheus/morpheus-ui/plugins/` although no plugin record
points at it.

## Cause (from the plugin API byte code)

- `PluginManager.registerPlugin(Class, File, String)`, after `plugin.initialize()`:
  `if (plugin.getControllers().size() > 0 && !plugin.hasCustomRender()) renderer.addTemplateLoader(plugin.getClassLoader())`.
- `HandlebarsRenderer.addTemplateLoader(ClassLoader)` calls
  `loader.addTemplateLoader(new HandlebarsPluginTemplateLoader(classLoader))`.
- `DynamicTemplateLoader(TemplateLoader...)` stores `delegates = Arrays.asList(loaders)`, and
  `addTemplateLoader(TemplateLoader)` calls `delegates.add(...)`. A list from `Arrays.asList` has a
  fixed size, so every call throws.

The same constructor (`Arrays.asList`) is in plugin API 1.4.1 and 1.4.2. The failure was measured
on 9.1.0 only.

## Workaround

Set a renderer on the plugin in `initialize()`. `hasCustomRender()` is then `true`, and the plugin
manager skips the shared loader:

```groovy
this.setRenderer(new HandlebarsRenderer('renderer', this.classLoader))
```

Templates resolve from the plugin's own `renderer/hbs/` folder, as with the providers' renderers.

## Suggested fix

- Keep the delegates in a mutable, thread-safe list, for example
  `new CopyOnWriteArrayList<>(Arrays.asList(loaders))`; `removeTemplateLoader` has the same
  problem.
- Report the real exception on upload instead of *Failed to parse plugin file*.
- Remove the jar file again when its registration fails.
