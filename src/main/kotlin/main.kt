import androidx.compose.animation.core.*
import androidx.compose.animation.transition
import androidx.compose.desktop.AppManager
import androidx.compose.desktop.AppWindow
import androidx.compose.desktop.Window
import androidx.compose.desktop.WindowEvents
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import jetbrains.exodus.ByteIterable
import jetbrains.exodus.bindings.BooleanBinding
import jetbrains.exodus.bindings.IntegerBinding
import jetbrains.exodus.bindings.StringBinding
import jetbrains.exodus.entitystore.EntityId
import jetbrains.exodus.entitystore.PersistentEntityStore
import jetbrains.exodus.entitystore.PersistentEntityStores
import jetbrains.exodus.env.Environment
import jetbrains.exodus.env.Environments.newInstance
import jetbrains.exodus.env.Store
import jetbrains.exodus.env.StoreConfig
import jetbrains.exodus.env.Transaction
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import org.jetbrains.skija.Image
import java.awt.Cursor
import java.awt.Point
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.lang.Boolean
import java.lang.reflect.Method
import javax.swing.JFrame


operator fun File.plus(segment: String): File = this.resolve(segment)


fun getOrCreateWorkingDir(): File {
    val os = (System.getProperty("os.name")).toUpperCase()
    val workingDir = if (os.contains("WIN")) {
        File(System.getenv("AppData")) + "Polaroid";
    } else {
        File(System.getProperty("user.home")) + ".polaroid";
    }
    if (!workingDir.exists()) {
        workingDir.mkdir()
    }
    return workingDir
}

enum class BufferState {
    BUFFER1, BUFFER2;

    fun otherState(): BufferState {
        return if (this == BUFFER1) {
            BUFFER2
        } else {
            BUFFER1
        }
    }
}

val imageIndex = IntPropKey()
val progress = FloatPropKey()

fun enableOSXFullscreen(window: Window?) {
    try {
        val util = Class.forName("com.apple.eawt.FullScreenUtilities")
        val params = arrayOf<Class<*>>(Window::class.java, Boolean.TYPE)
        val method: Method = util.getMethod("setWindowCanFullScreen", *params)
        method.invoke(util, window, true)
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
}

fun requestOSXFullscreen(window: Window?) {
    try {
        val appClass = Class.forName("com.apple.eawt.Application")
        val params = arrayOf<Class<*>>()
        val getApplication: Method = appClass.getMethod("getApplication", *params)
        val application: Any = getApplication.invoke(appClass)
        val requestToggleFulLScreen: Method =
            application.javaClass.getMethod("requestToggleFullScreen", Window::class.java)
        requestToggleFulLScreen.invoke(application, window)
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
}

val Double.mb get() = this * 1_000_000

fun isMacOs() = System.getProperty("os.name").toLowerCase().startsWith("mac os x");

data class PhotoMeta(val path: String, val id: EntityId? = null, val hidden: kotlin.Boolean = false) {
    override fun equals(other: Any?): kotlin.Boolean {
        return other is PhotoMeta && path == other.path && hidden == other.hidden
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + hidden.hashCode()
        return result
    }
}


suspend fun indexSourceDir(dir: File, entityStore: PersistentEntityStore) {
    val currentPhotos = entityStore.computeInReadonlyTransaction { txn ->
        val currentPhotos = mutableListOf<PhotoMeta>()
        txn.findStartingWith("Photo", "path", dir.absolutePath).iterator().forEach { entity ->
            val path = entity.getProperty("path") as String
            currentPhotos += PhotoMeta(path, entity.id)
        }
        currentPhotos
    }

    var foundPhotoCount = 0
    var newPhotoCount = 0

    dir.walkTopDown().iterator().asSequence().filter {
        it.isImage && !it.isHidden && it.length() > 0.5.mb
    }.map {
        PhotoMeta(it.absolutePath)
    }.chunked(10) { chunk ->
        val newPhotos = chunk.filter { !currentPhotos.contains(it) }
        entityStore.executeInTransaction { txn ->
            newPhotos.forEach { photo ->
                val photoEntity = txn.newEntity("Photo")
                photoEntity.setProperty("path", photo.path)
            }
        }
        chunk to newPhotos
    }.onEach {
        foundPhotoCount += it.first.size
        newPhotoCount += it.second.size
    }.toList()
    println("Done parsing file tree. Summary:")
    println("   Found: $foundPhotoCount")
    println("   New: $newPhotoCount")
}

fun String.toFile() = File(this)

fun Store.getOrPut(txn: Transaction, key: String, value: String): String {
    return getOrPut(txn, key, value, StringBinding::entryToString, StringBinding::stringToEntry)
}

fun Store.getOrPut(txn: Transaction, key: String, value: Int): Int {
    return getOrPut(txn, key, value, IntegerBinding::entryToInt, IntegerBinding::intToEntry)
}

fun Store.getOrPut(txn: Transaction, key: String, value: kotlin.Boolean): kotlin.Boolean {
    return getOrPut(txn, key, value, BooleanBinding::entryToBoolean, BooleanBinding::booleanToEntry)
}

fun <T> Store.getOrPut(
    txn: Transaction,
    key: String,
    value: T,
    convertTo: (ByteIterable) -> T,
    convertFrom: (T) -> ByteIterable
): T {
    val keyEntry = StringBinding.stringToEntry(key)
    val sourceDir = this.get(txn, keyEntry)
    return if (sourceDir != null) {
        val storedValue = convertTo(sourceDir)
        storedValue
    } else {
        this.put(txn, keyEntry, convertFrom(value))
        value
    }
}

fun <T> updateSetting(
    value: T,
    editedValue: T,
    setValue: (T) -> Unit,
    setEditedValue: (T) -> Unit,
    env: Environment,
    key: String,
    convertFrom: (T) -> ByteIterable,
    condition: (() -> kotlin.Boolean)? = null,
    onChanged: ((T) -> Unit)? = null
) {
    if (value != editedValue) {
        if (condition?.invoke() != false) {
            setValue(editedValue)
            GlobalScope.launch {
                env.executeInTransaction { txn ->
                    val store = env.openStore("Config", StoreConfig.WITHOUT_DUPLICATES, txn)
                    val sourceDirKey = StringBinding.stringToEntry(key)
                    store.put(txn, sourceDirKey, convertFrom(editedValue))
                }
                onChanged?.invoke(editedValue)
            }
        } else {
            setEditedValue(value)
        }
    }
}

fun main() = runBlocking {

    val env = newInstance(getOrCreateWorkingDir())

    var sourceDirString = System.getProperty("user.home")
    var initDuration = 30
    var initShowPath = true
    env.executeInTransaction { txn ->
        val store = env.openStore("Config", StoreConfig.WITHOUT_DUPLICATES, txn)
        sourceDirString = store.getOrPut(txn, "SourceDir", sourceDirString)
        initDuration = store.getOrPut(txn, "Duration", initDuration)
        initShowPath = store.getOrPut(txn, "ShowPath", initShowPath)
        println("Config:")
        println("   Source Dir: $sourceDirString")
        println("   Duration: $initDuration")
    }
    val sourceDir = File(sourceDirString)

    val entityStore = PersistentEntityStores.newInstance(env)

    GlobalScope.launch {
        indexSourceDir(sourceDir, entityStore)
    }

    var keyboardAdded = false
    var toggleSettings: () -> Unit = {
        println("Not Initialised")
    }

    val randomPhotoActor = randomPhotoActor(entityStore, sourceDirString)

    Window(
        undecorated = true,
        events = WindowEvents(
            onClose = {
                entityStore.close()
                env.close()
                randomPhotoActor.close()
            },
            onFocusGet = {
                if (!keyboardAdded) {
                    val keyboard = (AppManager.focusedWindow as? AppWindow)?.keyboard
                    if (keyboard != null) {
                        keyboard.setShortcut(Key.Escape) {
                            toggleSettings()
                        }
                        keyboardAdded = true
                    }
                }
                val window = AppManager.focusedWindow?.window
                if (window != null && window.extendedState != JFrame.MAXIMIZED_BOTH) {
                    window.extendedState = JFrame.MAXIMIZED_BOTH
                    if (isMacOs()) {
                        enableOSXFullscreen(window)
                        requestOSXFullscreen(window)
                    }
                    window.contentPane.cursor = window.toolkit.createCustomCursor(
                        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
                        Point(),
                        null
                    )
                }
            }
        )
    ) {

        val (settingsOpen, setSettingsOpen) = remember { mutableStateOf(false) }
        toggleSettings = {
            setSettingsOpen(!settingsOpen)
        }

        val window = AppManager.focusedWindow?.window
        if (window != null) {
            if (settingsOpen) {
                window.contentPane.cursor = Cursor.getDefaultCursor()
            } else {
                window.contentPane.cursor =
                    window.toolkit.createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(), null)
            }
        }

        var searchPath by remember { mutableStateOf(sourceDir.absolutePath) }
        var editedPath by remember { mutableStateOf(sourceDir.absolutePath) }
        val validPath = remember(editedPath) { File(editedPath).exists() }
        var bufferState by remember { mutableStateOf(BufferState.BUFFER1) }
        var duration by remember { mutableStateOf(initDuration) }
        var editedDuration by remember { mutableStateOf(initDuration) }
        var showPath by remember { mutableStateOf(initShowPath) }
        var editedShowPath by remember { mutableStateOf(initShowPath) }

        val transDef = remember(duration, bufferState) {
            transitionDefinition<BufferState> {

                state(BufferState.BUFFER1) {
                    this[imageIndex] = 0
                    this[progress] = 0f
                }
                state(BufferState.BUFFER2) {
                    this[imageIndex] = 1
                    this[progress] = 1f
                }

                transition(BufferState.BUFFER1 to BufferState.BUFFER2) {
                    imageIndex using snap(duration * 1000)
                    progress using tween(duration * 1000)
                }
                transition(BufferState.BUFFER2 to BufferState.BUFFER1) {
                    imageIndex using snap(duration * 1000)
                    progress using tween(duration * 1000)
                }
            }
        }

        val state = transition(
            transDef,
            initState = bufferState,
            toState = bufferState.otherState()
        )

        if (!settingsOpen) {
            updateSetting(
                searchPath,
                editedPath,
                { searchPath = it },
                { editedPath = it },
                env,
                "SourceDir",
                StringBinding::stringToEntry,
                { validPath }) {
                GlobalScope.launch {
                    indexSourceDir(File(it), entityStore)
                }
            }
            updateSetting(
                duration,
                editedDuration,
                { duration = it },
                { editedDuration = it },
                env,
                "Duration",
                IntegerBinding::intToEntry,
                { editedDuration > 0 })
            updateSetting(
                showPath,
                editedShowPath,
                { showPath = it },
                { editedShowPath = it },
                env,
                "ShowPath",
                BooleanBinding::booleanToEntry
            )
        }


        var index by remember { mutableStateOf(0) }
        resolveBufferState(state, index, bufferState) { bufferState = it }
        resolveIndex(state, index) { index = it }
        val (photo, setPhoto) = remember { mutableStateOf<PhotoMeta?>(null) }
        randomPhotoFile(searchPath, index, settingsOpen, setPhoto, randomPhotoActor)

        Column(modifier = Modifier.fillMaxSize()) {

            if (settingsOpen) {
                val padding = remember { 10.dp }
                Row(modifier = Modifier.padding(padding).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            setSettingsOpen(false)
                        }) {
                            Text("Speichern")
                        }
                        Spacer(modifier = Modifier.preferredSize(padding))
                        TextField(editedPath, label = {
                            Text("Bilderverzeichnis")
                        }, maxLines = 1, onValueChange = {
                            editedPath = it
                        }, isErrorValue = !validPath)
                        Spacer(modifier = Modifier.preferredSize(padding))
                        var validDuration by remember(duration) { mutableStateOf(true) }
                        TextField(if (editedDuration > 0) editedDuration.toString() else "", label = {
                            Text("Anzeige Dauer (in Sekunden)")
                        }, maxLines = 1, onValueChange = {
                            if (it.matches("[0-9]*".toRegex())) {
                                if (it == "") {
                                    editedDuration = 0
                                    validDuration = false
                                } else {
                                    editedDuration = it.toInt()
                                    validDuration = true
                                }
                            } else {
                                validDuration = false
                            }
                        }, isErrorValue = !validDuration)
                        Spacer(modifier = Modifier.preferredSize(padding))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Bildpfad anzeigen")
                            Switch(editedShowPath, onCheckedChange = {
                                editedShowPath = it
                            })
                        }
                        Spacer(modifier = Modifier.preferredSize(padding))
                        Button(onClick = {
                            if (photo != null) {
                                val photoCopy = photo.copy()
                                GlobalScope.launch {
                                    changeFileVisibility(File(photoCopy.path), photoCopy.hidden, entityStore)
                                }
                                setPhoto(photo.copy(hidden = !photo.hidden))
                            }

                        }) {
                            Text(if (photo?.hidden == true) "Bild nicht mehr verbergen" else "Bild verbergen")
                        }
                    }
                    Button(onClick = {
                        AppManager.exit()
                    }, colors = ButtonDefaults.buttonColors(MaterialTheme.colors.error)) {
                        val unchanged =
                            editedShowPath == showPath && editedDuration == duration && editedPath == searchPath
                        Text(if (unchanged) "Beenden" else "Beenden und Einstellugen verwerfen")
                    }
                }

            }
            Column(
                modifier = Modifier.fillMaxSize().background(Color.Black)
            ) {
                Photo(photo, duration, if (index == 0) state[progress] else 1 - state[progress], editedShowPath)
            }
        }

    }

}

@Composable
fun Test(state: TransitionState) {
    println(state[imageIndex])
}

@Composable
fun resolveIndex(state: TransitionState, index: Int, setIndex: (Int) -> Unit) {
    if (index != state[imageIndex]) {
        setIndex(state[imageIndex])
    }
}

@Composable
fun resolveBufferState(
    state: TransitionState,
    index: Int,
    bufferState: BufferState,
    setBufferState: (BufferState) -> Unit
) {
    if (index != state[imageIndex]) {
        setBufferState(bufferState.otherState())
    }
}

@Composable
fun Photo(
    photo: PhotoMeta?,
    duration: Int,
    photoProgress: Float,
    showPath: kotlin.Boolean
) {
    val image = remember(photo?.path) { if (photo != null) imageFromFile(photo.path.toFile()) else null }
    if (photo != null && image != null) {
        Box {
            Image(
                bitmap = image,
                modifier = Modifier.fillMaxSize().alpha(if (photo.hidden) 0.5f else 1f)
            )
            if (showPath && (photoProgress > 0.8 || duration < 4)) {
                Text(
                    photo.path, color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color.Black)
                        .alpha(if (duration < 4) 1f else (photoProgress - 0.8f) / 0.2f)
                        .padding(10.dp)
                )
            }
        }
    } else {
        Text("Loading", color = Color.White)
    }

}

@Composable
fun randomPhotoFile(
    searchPath: String,
    bufferIndex: Int,
    paused: kotlin.Boolean,
    setImagePhoto: (PhotoMeta?) -> Unit,
    randomPhotoChannel: SendChannel<PhotoMsg>
) {
    if (!paused) {
        LaunchedEffect(bufferIndex) {
            println("Next Photo")
            val response = CompletableDeferred<PhotoMeta?>()
            randomPhotoChannel.send(GetRandomPhoto(response, searchPath))
            setImagePhoto(response.await())
        }
    }
}

fun changeFileVisibility(file: File, visible: kotlin.Boolean, entityStore: PersistentEntityStore) {
    entityStore.executeInTransaction { txn ->
        val iterator = txn.find("Photo", "path", file.absolutePath).iterator()
        iterator.forEach { entity ->
            val hidden = if (visible) "false" else "true"
            println("Changing hidden to $hidden")
            entity.setProperty("hidden", hidden)
        }
    }
}

sealed class PhotoMsg
class GetRandomPhoto(val photo: CompletableDeferred<PhotoMeta?>, val searchPath: String) : PhotoMsg()

fun CoroutineScope.randomPhotoActor(entityStore: PersistentEntityStore, sourcePath: String) = actor<PhotoMsg> {
    var currentSearchPath = sourcePath
    val ids = mutableListOf<EntityId>()
    fun loadIds(sourcePath: String) {
        ids.clear()
        entityStore.executeInReadonlyTransaction { txn ->
            val iterator =
                txn.findStartingWith("Photo", "path", sourcePath).minus(txn.find("Photo", "hidden", "true")).iterator()
            while (iterator.hasNext()) {
                val currentId = iterator.nextId()!!
                ids += currentId
            }
        }
    }
    loadIds(currentSearchPath)
    for (msg in channel) {
        when (msg) {
            is GetRandomPhoto -> {
                if (msg.searchPath != currentSearchPath) {
                    currentSearchPath = msg.searchPath
                    loadIds(currentSearchPath)
                }
                val randomId = ids.randomOrNull()
                if (randomId != null) {
                    val photo = entityStore.computeInReadonlyTransaction { txn ->
                        val entity = txn.getEntity(randomId)
                        val path = entity.getProperty("path") as String
                        val hidden = (entity.getProperty("hidden") as? String)?.let { it == "true" } ?: false
                        PhotoMeta(path, randomId, hidden)
                    }
                    msg.photo.complete(photo)
                } else {
                    msg.photo.complete(null)
                }
            }
        }
    }
}

fun randomImageFile(sourcePath: String, entityStore: PersistentEntityStore): File? {
    val ids = mutableListOf<EntityId>()
    var file: File? = null
    try {
        entityStore.executeInReadonlyTransaction { txn ->
            val iterator = txn.find("Photo", "source", sourcePath).minus(txn.find("Photo", "hidden", "true")).iterator()
            while (iterator.hasNext()) {
                val currentId = iterator.nextId()!!
                ids += currentId
            }
            val randomId = ids.randomOrNull()
            if (randomId != null) {
                val path = txn.getEntity(randomId).getProperty("path") as String
                file = File(path)
            }
        }
    } catch (e: Exception) {
        println("Refresh failed")
        println(e)
        file = File(sourcePath).walkTopDown().iterator().asSequence().filter {
            it.isImage && !it.isHidden && it.length() > 0.5.mb
        }.take(10).toList().random()
    }
    return file
}

fun imageFromFile(file: File): ImageBitmap? {
    return try {
        Image.makeFromEncoded(file.readBytes()).asImageBitmap()
    } catch (e: Exception) {
        println("Error while loading image from file '$file':")
        println(e.localizedMessage)
        println(e.stackTrace)
        null
    }

}

val File.isImage get() = this.extension.matches("jpe?g|png".toRegex(RegexOption.IGNORE_CASE))