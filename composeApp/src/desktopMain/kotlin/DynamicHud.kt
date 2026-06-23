package fit
import java.net.URL
import java.net.URLClassLoader
import java.io.File
import fit.HudConfig
import fit.HudCanvas
import fit.FitParser.TelemetryPoint

class HotReloadClassLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // HudRendererとその内部クラスだけはこのクラスローダから直接読み込む
        if (name == "fit.HudRenderer" || name.startsWith("fit.HudRenderer\$")) {
            val c = findLoadedClass(name)
            if (c != null) return c
            try {
                return findClass(name)
            } catch (e: ClassNotFoundException) {
            }
        }
        return super.loadClass(name, resolve)
    }
}

class DynamicRendererProxy(private val config: HudConfig) {
    private var delegate: Any? = null
    private var renderMethod: java.lang.reflect.Method? = null
    private var previousTempDir: File? = null
    private var previousClassLoader: java.net.URLClassLoader? = null

    init {
        reload()
    }

    fun reload(): Boolean {
        try {
            val classesDir = File(System.getProperty("user.dir"), "shared-core/build/classes/kotlin/desktop/main")
            if (!classesDir.exists()) return false
            val tempDir = File(System.getProperty("java.io.tmpdir"), "hud_hotreload_${System.currentTimeMillis()}")
            classesDir.copyRecursively(tempDir, overwrite = true)
            
            val cl = HotReloadClassLoader(arrayOf(tempDir.toURI().toURL()), this.javaClass.classLoader)
            val clazz = cl.loadClass("fit.HudRenderer")
            val constructor = clazz.getDeclaredConstructor(HudConfig::class.java)
            delegate = constructor.newInstance(config)
            renderMethod = clazz.getDeclaredMethod("renderFrame", 
                HudCanvas::class.java, 
                TelemetryPoint::class.java, 
                List::class.java, 
                List::class.java, 
                Float::class.java
            )
            println("✅ Hot reloaded HudRenderer successfully!")
            
            // Clean up previous temp dir to prevent infinite accumulation
            previousClassLoader?.let {
                try { it.close() } catch (e: Exception) { /* ignore */ }
            }
            previousTempDir?.let {
                try { it.deleteRecursively() } catch (e: Exception) {
                    println("⚠️ Failed to delete previous temp dir: ${e.message}")
                }
            }
            previousClassLoader = cl
            previousTempDir = tempDir
            
            return true
        } catch (e: Exception) {
            println("❌ Failed to hot reload: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    fun renderFrame(canvas: HudCanvas, point: TelemetryPoint, allPoints: List<TelemetryPoint>, powerBuffer: List<Double>, progressRatio: Float) {
        if (delegate == null) reload()
        renderMethod?.invoke(delegate, canvas, point, allPoints, powerBuffer, progressRatio)
    }
}
