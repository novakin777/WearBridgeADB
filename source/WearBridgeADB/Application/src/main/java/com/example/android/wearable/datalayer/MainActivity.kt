package dev.wearbrdgeadb

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.*
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

class MainActivity : Activity(), MessageClient.OnMessageReceivedListener {
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
 private lateinit var output: TextView
 private var hasOutput=false
 private lateinit var command: EditText
 private lateinit var mode: TextView
 private lateinit var connectionStatus: TextView
 private lateinit var pageContainer: LinearLayout
 private lateinit var destinationPages: List<View>
 private lateinit var pageScrollView: ScrollView
 private lateinit var appHeader: View
 private lateinit var appNavigation: View
 private lateinit var terminalPage: LinearLayout
 private lateinit var terminalConsole: LinearLayout
 private lateinit var fullscreenButton: Button
 private var fullscreenTerminal=false
 private var fullscreenDialog:Dialog?=null
 private var fullscreenFrame:FrameLayout?=null
 private var fullscreenView:BridgeTerminalView?=null
 private var language="en"
 private var terminalFontSizeSp=15
 private var terminalKeysExpanded=false
 private var fullscreenPlainOutput:TextView?=null
 private val pageScrollPositions=IntArray(3)
 private var currentDestination=0
 private lateinit var outputScroll: ScrollView
 private lateinit var terminalKeysPanel: HorizontalScrollView
 private val terminalKeyButtons=ArrayList<Pair<Button,String>>()
 private lateinit var navTerminal: Button
 private lateinit var navFiles: Button
 private lateinit var navSettings: Button
 private lateinit var normalModeButton: Button
 private lateinit var streamModeButton: Button
 private lateinit var stopButton: Button
 private lateinit var disconnectShellButton: Button
 private lateinit var privilegeButton: Button
 private lateinit var apiButton: Button
 private var stream=false
 private var streamAttempt=0
 private var rootMode=false
 private var streamReady=false
 private val pendingInput=ArrayList<ByteArray>()
 private var pendingBytes=0
 private var nodeId:String?=null
 private var channel:ChannelClient.Channel?=null
 private var channelInput:java.io.InputStream?=null
 private var channelOutput:java.io.OutputStream?=null
 private data class PendingFileTransfer(val id:String,val key:ByteArray,val operation:String,val path:String,val uri:Uri?,val root:Boolean)
 private var pendingFileTransfer:PendingFileTransfer?=null
 private data class QueuedFileTransfer(val operation:String,val path:String,val uri:Uri,val root:Boolean)
 private val fileTransferQueue=java.util.ArrayDeque<QueuedFileTransfer>()
 private var transferBatchTotal=0
 private var transferBatchDone=0
 private var transferBatchFailed=0
 private var pendingRemotePath=""
 private var pendingFileOperation=""
 private var pendingFileRoot=false
 private var browserPath="/sdcard"
 private data class UploadSource(val relativePath:String,val uri:Uri)
 private var browserUploadSources:List<UploadSource> = emptyList()
 private var browserUploadIsFolder=false
 private var browserUploadFolders:List<String> = emptyList()
 private val selectedRemotePaths=linkedMapOf<String,Boolean>()
 private var remoteSelectionToolbar:LinearLayout?=null
 private var remoteSelectionCount:TextView?=null
 private var remoteConfirmButton:Button?=null
 private val visibleRemoteChecks=linkedMapOf<String,CheckBox>()
 private data class DownloadSource(val remotePath:String,val relativePath:String)
 private data class DownloadPlan(val files:List<DownloadSource>,val directories:List<String>)
 private var pendingDownloads:List<DownloadSource> = emptyList()
 private val listingWaiters=java.util.concurrent.ConcurrentHashMap<String,CompletableDeferred<JSONObject>>()
 private val directoryWaiters=java.util.concurrent.ConcurrentHashMap<String,CompletableDeferred<JSONObject>>()
 private val deleteWaiters=java.util.concurrent.ConcurrentHashMap<String,CompletableDeferred<JSONObject>>()
 private var confirmDelete=true
 private var fileBatchActive=false
 private var browserRequestId:String?=null
 private var rootBrowserConfirmed=false
 private var browserOffset=0
 private data class RemoteEntry(val name:String,val directory:Boolean,val size:Long,val modifiedEpoch:Long)
 private val remoteEntries=ArrayList<RemoteEntry>()
 private var browserPage=0
 private var browserSort=0
 private val commandHistory=ArrayList<String>()
 private val pendingNormalRequests=linkedSetOf<String>()
 private var historyIndex=0
 private var historyDraft=""
 private var pendingNearbyPermissionAction:(()->Unit)?=null
 private val writes=Dispatchers.IO
 private val writeMutex=Mutex()
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  language=getSharedPreferences("wearbridge_ui",MODE_PRIVATE).getString("language","en") ?: "en"
  terminalFontSizeSp=getSharedPreferences("wearbridge_ui",MODE_PRIVATE).getInt("terminal_font_sp",15).coerceIn(12,22)
  rootMode=getSharedPreferences(TermuxApiService.PREFS,MODE_PRIVATE).getBoolean("root_mode",false)
  confirmDelete=getSharedPreferences("wearbridge_files",MODE_PRIVATE).getBoolean("confirm_delete",true)

  val shell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(color(R.color.wb_canvas))}
  val header=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(6),dp(20),dp(6));setBackgroundColor(color(R.color.wb_canvas))}
  appHeader=header
  val brandRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
  val brand=TextView(this).apply{text=t("WEARBRIDGE");textSize=12f;letterSpacing=.16f;setTextColor(color(R.color.wb_primary));typeface=Typeface.DEFAULT_BOLD;contentDescription=t("WearBridge device console")}
  brandRow.addView(brand,LinearLayout.LayoutParams(0,dp(28),1f))
  connectionStatus=TextView(this).apply{text=t("Finding watch…");textSize=12f;setTextColor(color(R.color.wb_warning));gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(10),0,dp(10),0);background=rounded(color(R.color.wb_surface_raised),dp(18));contentDescription=t("Watch connection status");accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
  brandRow.addView(connectionStatus,LinearLayout.LayoutParams(-2,dp(32)))
  header.addView(brandRow)

  pageContainer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(color(R.color.wb_canvas))}
  val contentFrame=FrameLayout(this).apply{addView(pageContainer,FrameLayout.LayoutParams(-1,if(isLandscape())-2 else -1))}
  pageScrollView=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=false;clipToPadding=false;addView(contentFrame,-1,-2)}
  shell.addView(header,LinearLayout.LayoutParams(-1,-2))
  shell.addView(pageScrollView,LinearLayout.LayoutParams(-1,0,1f))

  val bottomBar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER;setPadding(dp(12),dp(6),dp(12),dp(6));background=rounded(color(R.color.wb_surface),dp(22));elevation=dp(5).toFloat();contentDescription=t("Main navigation")}
  navTerminal=navButton("Terminal",0);navFiles=navButton("Files",1);navSettings=navButton("Settings",2)
  bottomBar.addView(navTerminal,LinearLayout.LayoutParams(0,dp(52),1f));bottomBar.addView(navFiles,LinearLayout.LayoutParams(0,dp(52),1f));bottomBar.addView(navSettings,LinearLayout.LayoutParams(0,dp(52),1f))
  val navWrap=FrameLayout(this).apply{setPadding(dp(16),dp(5),dp(16),dp(8));addView(bottomBar,FrameLayout.LayoutParams(-1,-2))}
  appNavigation=navWrap
  shell.addView(navWrap,LinearLayout.LayoutParams(-1,-2))
  setContentView(shell)
  enableEdgeToEdge(shell)
  window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
  Wearable.getMessageClient(this).addListener(this)
  navigateTo(0)
  refreshApiButton()
  scope.launch{refreshConnection()}
 }

 private fun setLanguage(next:String){
  if(language==next)return
  val oldCommand=command.text.toString()
  val oldOutput=output.text.toString()
  val hadOutput=hasOutput
  language=next
  getSharedPreferences("wearbridge_ui",MODE_PRIVATE).edit().putString("language",next).apply()
  navTerminal.text=t("Terminal");navFiles.text=t("Files");navSettings.text=t("Settings")
  terminalKeyButtons.clear()
  pageContainer.removeAllViews()
  destinationPages=listOf(buildTerminalPage(),buildFilesPage(),buildSettingsPage())
  destinationPages.forEachIndexed{index,page->pageContainer.addView(page,LinearLayout.LayoutParams(-1,if(index==0&&!isLandscape())-1 else -2));page.visibility=if(index==currentDestination)View.VISIBLE else View.GONE}
  command.setText(oldCommand)
  hasOutput=hadOutput
  if(hadOutput)output.text=oldOutput
  refreshModeStatus()
  scope.launch{refreshConnection()}
 }
 private fun t(value:String):String {
  if(language!="ru")return value
  return when(value){
   "Terminal"->"Терминал";"Files"->"Файлы";"Settings"->"Настройки";"Finding watch…"->"Поиск часов…";"WATCH CONNECTED"->"ЧАСЫ ПОДКЛЮЧЕНЫ";"WATCH OFFLINE"->"ЧАСЫ НЕ В СЕТИ";"CONNECTION ERROR"->"ОШИБКА СОЕДИНЕНИЯ"
   "COMMAND MODE · MANUAL"->"РЕЖИМ КОМАНД · ВРУЧНУЮ";"COMMAND / TERMINAL INPUT"->"КОМАНДА / ВВОД ТЕРМИНАЛА";"Enter a command"->"Введите команду";"Run command"->"Выполнить";"STOP · Ctrl+C"->"СТОП · Ctrl+C";"Keys +"->"Клавиши +";"Keys −"->"Клавиши −";"Backspace"->"Стереть";"OUTPUT"->"ВЫВОД";"Disconnect"->"Отключить";"Close shell"->"Закрыть shell";"Clear"->"Очистить";"Copy"->"Копировать";"Exit"->"Выход";"TERMINAL"->"ТЕРМИНАЛ";"Terminal font size"->"Размер шрифта терминала";"TERMINAL FONT"->"ШРИФТ ТЕРМИНАЛА";"Clear terminal display"->"Очистить экран терминала";"Run in NORMAL or connect STREAM."->"Выполните команду в NORMAL или подключите STREAM."
   "Watch files"->"Файлы часов";"Browse storage, move files, or remove selected items."->"Просматривайте хранилище, переносите и удаляйте файлы.";"WATCH STORAGE"->"ХРАНИЛИЩЕ ЧАСОВ";"Browse & download"->"Просмотр и скачивание";"SEND TO WATCH"->"ОТПРАВИТЬ НА ЧАСЫ";"Choose files"->"Выбрать файлы";"Choose a folder"->"Выбрать папку";"Downloads use the selected phone location. Upload and download keep folder structure for batches. ROOT access is controlled in Settings."->"Скачивание идёт в выбранное место телефона. Структура папок сохраняется. ROOT включается в настройках."
   "Choose how watch commands run and manage the local client API."->"Выберите профиль команд и настройте локальный API.";"LANGUAGE"->"ЯЗЫК";"WATCH EXECUTION PROFILE"->"ПРОФИЛЬ ВЫПОЛНЕНИЯ";"SHELL uses the Shizuku identity. ROOT uses the watch's separately granted KernelSU access."->"SHELL использует Shizuku. ROOT требует отдельного разрешения KernelSU на часах.";"TERMUX API"->"TERMUX API";"Copy API token"->"Скопировать API-токен";"API token copied. Save it in Termux with wadb configure."->"API-токен скопирован. Сохраните его в Termux командой wadb configure.";"Start the API once before copying its token."->"Сначала один раз запустите API, затем скопируйте токен.";"The API token grants command access to this phone's WearBridge. Keep it private."->"API-токен даёт доступ к выполнению команд WearBridge на этом телефоне. Храните его в секрете.";"CONNECTION"->"СОЕДИНЕНИЕ";"Wear Data Layer over the existing watch pairing"->"Wear Data Layer через сопряжение с часами";"Commands and terminal bytes use the paired connection. Wi-Fi is used only for direct file bytes when available."->"Команды и терминал работают через сопряжение. Wi-Fi нужен только для прямой передачи файлов.";"The API is available only on this phone at 127.0.0.1:${TermuxApiService.PORT}; it is not exposed to the local network."->"API доступен только на телефоне: 127.0.0.1:${TermuxApiService.PORT}. В локальной сети он закрыт."
   "Name A–Z"->"Имя А–Я";"Date modified"->"Дата изменения";"Size"->"Размер";"Type"->"Тип";"Name"->"Имя";"Date"->"Дата";"Name · A–Z"->"Имя · А–Я";"Date modified · newest first"->"Дата · сначала новые";"Size · largest first"->"Размер · сначала большие";"Type · extension"->"Тип · расширение";"Open shared storage"->"Открыть общую память";"Go to filesystem root"->"В корень файловой системы";"Already at filesystem root"->"Уже в корне файловой системы";"Go to parent folder"->"На уровень выше";"Open shared storage at /sdcard"->"Открыть общую память /sdcard";"Close browser"->"Закрыть браузер";"Sort by Name"->"Сортировка: имя";"Ask before delete · On"->"Спрашивать перед удалением · вкл";"Ask before delete · Off"->"Спрашивать перед удалением · выкл";"Select files or folders first"->"Сначала выберите файлы или папки";"No phone files were selected"->"Файлы телефона не выбраны";"No files were selected"->"Файлы не выбраны";"Wait for the active transfer"->"Дождитесь окончания передачи";"Loading folder…"->"Загрузка папки…";"Terminal output is empty"->"Вывод терминала пуст";"Terminal output copied"->"Вывод терминала скопирован";"Terminal selection copied"->"Выделенный текст скопирован";"ROOT file access"->"Доступ к файлам от ROOT";"This operation will run as root on the watch."->"Операция будет выполнена на часах с правами ROOT.";"More"->"Далее";"Previous"->"Назад";"Files on Xiaomi Watch 5"->"Файлы Xiaomi Watch 5";"Close"->"Закрыть";"Root"->"Корень";"Storage"->"Хранилище";"Sort files"->"Сортировка";"Cancel"->"Отмена";"Delete"->"Удалить";"Continue"->"Продолжить";"Nothing here yet"->"Здесь пока пусто";"This folder is empty."->"Папка пуста.";"This folder is empty. Choose another folder or upload files here."->"Папка пуста. Откройте другую или загрузите файлы сюда.";"Folders first"->"Сначала папки";"Upload here"->"Загрузить сюда";"DOWNLOAD FILES / FOLDERS"->"СКАЧАТЬ ФАЙЛЫ / ПАПКИ";"UPLOAD FILES"->"ЗАГРУЗИТЬ ФАЙЛЫ";"UPLOAD FOLDER"->"ЗАГРУЗИТЬ ПАПКУ";"Select multiple files or folders. Folder transfers keep their directory structure."->"Выберите несколько файлов или папок. Структура папок сохранится.";"Do not ask before deleting"->"Больше не спрашивать перед удалением"
   "Allow Nearby devices to transfer files over Wi-Fi"->"Разрешите доступ к устройствам поблизости для передачи по Wi-Fi";"Choose the destination folder, then tap Upload here"->"Выберите папку назначения и нажмите «Загрузить сюда»";"Clear console output"->"Очистить вывод";"Clear selection"->"Снять выделение";"Close shell session. This does not send Ctrl+C to the foreground process."->"Закрыть shell-сессию. Это не Ctrl+C для текущего процесса.";"Command or terminal input"->"Ввод команды или терминала";"Console output"->"Вывод консоли";"Copy all console output"->"Скопировать весь вывод";"Delete selected files and folders"->"Удалить выбранные файлы и папки";"Download selected files"->"Скачать выбранные файлы";"Execution mode"->"Режим выполнения";"Exit fullscreen terminal"->"Выйти из полноэкранного терминала";"Main navigation"->"Основная навигация";"More watch storage actions"->"Дополнительные действия";"Open fullscreen terminal"->"Открыть полноэкранный терминал";"Run command or send terminal input"->"Выполнить команду или отправить ввод";"Selectable terminal output"->"Выделяемый вывод терминала";"Selected items"->"Выбранные элементы";"Send Ctrl+C to the foreground terminal process"->"Отправить Ctrl+C текущему процессу";"Show terminal control keys"->"Показать терминальные клавиши";"Hide terminal control keys"->"Скрыть терминальные клавиши";"Terminal control keys shown"->"Терминальные клавиши показаны";"Terminal control keys hidden"->"Терминальные клавиши скрыты";"Start or stop the Termux loopback API"->"Запустить или остановить локальный Termux API";"Terminal output"->"Вывод терминала";"Terminal output; earlier output trimmed to limit memory use"->"Вывод терминала; старые данные сокращены";"Terminal output; no command output yet"->"Вывод терминала пока пуст";"Toggle Control modifier"->"Переключить модификатор Ctrl";"Toggle watch execution profile between SHELL and ROOT"->"Переключить профиль часов между SHELL и ROOT";"Watch connection status"->"Состояние подключения часов";"WearBridge device console"->"Консоль часов WearBridge";"… earlier output trimmed …\n"->"… старый вывод сокращён …\n"
   "›  Output will appear here\n   Run in NORMAL or connect STREAM."->"›  Вывод появится здесь\n   Выполните команду в NORMAL или подключите STREAM.";"NORMAL · ready"->"NORMAL · готово";"STREAM · connecting…"->"STREAM · подключение…";"STREAM · disconnected"->"STREAM · отключён";"Waiting for paired watch"->"Ожидание сопряжённых часов";"NORMAL · terminal closed"->"NORMAL · терминал закрыт";"ROOT · ON"->"ROOT · ВКЛ";"SHELL · ACTIVE"->"SHELL · АКТИВЕН";"TERMUX API · RUNNING"->"TERMUX API · РАБОТАЕТ";"TERMUX API · STOPPED"->"TERMUX API · ОСТАНОВЛЕН"
   else -> when {
    value.contains(" items  ·  Sort: ") -> value.replace(" items  ·  Sort: "," элементов · Сортировка: ")
    value.startsWith("Ask before delete · ") -> value.replace("Ask before delete · ","Спрашивать перед удалением · ").replace("On","вкл").replace("Off","выкл")
    value.startsWith("Sort by ") -> value.replace("Sort by ","Сортировка: ")
    value.endsWith(" selected") -> value.removeSuffix(" selected")+" выбрано"
    value.startsWith("Page ") && value.contains(" of ") -> value.replace("Page ","Страница ").replace(" of "," из ")
    value.endsWith(" command(s) running") -> "NORMAL · выполняется команд: "+value.substringAfter("NORMAL · ").substringBefore(' ')
    value.startsWith("STREAM · ") && value.endsWith(" PTY connected") -> value.replace(" PTY connected"," PTY подключён")
    value.startsWith("Browse ") -> "Просмотр "+value.removePrefix("Browse ")
    value.startsWith("Download ") -> "Скачать "+value.removePrefix("Download ").replace(" selected items"," выбранных элементов")
    value.startsWith("Upload ") -> "Загрузить "+value.removePrefix("Upload ").replace(" file(s) to "," файлов в ")
    value.startsWith("Cannot browse ") -> value.replace("Cannot browse ","Не открыть ")
    value.startsWith("Loading ") -> value.replace("Loading ","Загрузка ")
    value.startsWith("Delete confirmation ") -> value.replace("Delete confirmation enabled","Подтверждение удаления включено").replace("Delete confirmation disabled","Подтверждение удаления выключено")
    value.startsWith("Deleted ") -> value.replace("Deleted ","Удалено: ").replace(" item(s)"," элементов")
    value.startsWith("Delete ") && value.endsWith(" item(s)?") -> "Удалить "+value.removePrefix("Delete ").removeSuffix(" item(s)?")+" элементов?"
    else -> value
   }
  }
 }
 private fun isLandscape()=resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
 override fun onConfigurationChanged(newConfig:Configuration){
  super.onConfigurationChanged(newConfig)
  if(!::pageContainer.isInitialized)return
  (pageContainer.layoutParams as? FrameLayout.LayoutParams)?.let{it.height=if(isLandscape())-2 else -1;pageContainer.layoutParams=it}
  if(::destinationPages.isInitialized){
   destinationPages[0].layoutParams=LinearLayout.LayoutParams(-1,if(isLandscape())-2 else -1)
   terminalConsole.layoutParams=LinearLayout.LayoutParams(-1,if(isLandscape())dp(360) else 0,if(isLandscape())0f else 1f).apply{topMargin=dp(10)}
  }
  pageScrollView.requestLayout()
  fullscreenDialog?.window?.setLayout(-1,-1)
  fullscreenView?.requestLayout()
 }
 private fun color(id:Int)=if(android.os.Build.VERSION.SDK_INT>=23)getColor(id) else resources.getColor(id)
 private fun dp(value:Int)= (value*resources.displayMetrics.density+.5f).toInt()
 private fun rounded(color:Int,radius:Int)=GradientDrawable().apply{setColor(color);cornerRadius=radius.toFloat()}
 private fun outlined(color:Int,radius:Int,stroke:Int)=GradientDrawable().apply{setColor(color);cornerRadius=radius.toFloat();setStroke(dp(1),stroke)}
 private fun space(height:Int)=View(this).apply{layoutParams=LinearLayout.LayoutParams(1,dp(height))}
 private fun label(value:String)=TextView(this).apply{text=t(value);textSize=11f;letterSpacing=.08f;typeface=Typeface.DEFAULT_BOLD;setTextColor(color(R.color.wb_on_surface_variant));setPadding(0,dp(10),0,dp(8))}
 private fun copyText(value:String,size:Float=14f)=TextView(this).apply{text=t(value);textSize=size;setTextColor(color(R.color.wb_on_surface_variant));setLineSpacing(dp(2).toFloat(),1f)}
 private fun navButton(title:String,destination:Int)=Button(this).apply{text=t(title);textSize=12f;isAllCaps=false;typeface=Typeface.DEFAULT_BOLD;minHeight=dp(48);minimumHeight=dp(48);setOnClickListener{navigateTo(destination)}}
 private fun navigateTo(destination:Int){
  if(fullscreenTerminal)exitFullscreenTerminal()
  val alreadyBuilt=::destinationPages.isInitialized
  if(alreadyBuilt)pageScrollPositions[currentDestination]=pageScrollView.scrollY
  if(!::destinationPages.isInitialized){
   destinationPages=listOf(buildTerminalPage(),buildFilesPage(),buildSettingsPage())
   destinationPages.forEachIndexed{index,page->pageContainer.addView(page,LinearLayout.LayoutParams(-1,if(index==0&&!isLandscape())-1 else -2))}
  }
  currentDestination=destination
  destinationPages.forEachIndexed{index,page->page.visibility=if(index==destination)View.VISIBLE else View.GONE}
  pageScrollView.post{pageScrollView.scrollTo(0,pageScrollPositions[destination])}
  listOf(navTerminal,navFiles,navSettings).forEachIndexed{index,button->
   val active=index==destination;button.isSelected=active;button.background=if(active)rounded(color(R.color.wb_surface_raised),dp(16)) else null
   button.setTextColor(color(if(active)R.color.wb_primary else R.color.wb_on_surface_variant))
  }
 }
 private fun buildTerminalPage():View{
  val page=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(4),dp(20),dp(4));setBackgroundColor(color(R.color.wb_canvas))}
  terminalPage=page
  page.addView(label("COMMAND MODE · MANUAL").apply{setPadding(0,0,0,dp(5))})
  val modeGroup=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(dp(4),dp(4),dp(4),dp(4));background=rounded(color(R.color.wb_surface),dp(18));contentDescription=t("Execution mode")}
  normalModeButton=Button(this).apply{text=t("NORMAL");isAllCaps=false;textSize=13f;typeface=Typeface.DEFAULT_BOLD;minHeight=dp(48);minimumHeight=dp(48);setOnClickListener{setNormal()}}
  streamModeButton=Button(this).apply{text=t("STREAM");isAllCaps=false;textSize=13f;typeface=Typeface.DEFAULT_BOLD;minHeight=dp(48);minimumHeight=dp(48);setOnClickListener{setStream()}}
  modeGroup.addView(normalModeButton,LinearLayout.LayoutParams(0,dp(50),1f));modeGroup.addView(streamModeButton,LinearLayout.LayoutParams(0,dp(50),1f));page.addView(modeGroup);refreshModeButtons()
  mode=TextView(this).apply{text=t("NORMAL · ready");textSize=12f;setTextColor(color(R.color.wb_on_surface_variant));setPadding(dp(4),dp(4),dp(4),dp(3));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
  page.addView(mode)
  page.addView(label("COMMAND / TERMINAL INPUT"))
  command=EditText(this).apply{
   id=View.generateViewId();hint=t("Enter a command");minLines=1;maxLines=4;maxHeight=dp(120);minHeight=dp(56);isVerticalScrollBarEnabled=true;setHorizontallyScrolling(false);gravity=android.view.Gravity.TOP or android.view.Gravity.START;textSize=15f;setSingleLine(false);setTextColor(color(R.color.wb_on_surface));setHintTextColor(color(R.color.wb_on_surface_variant));setPadding(dp(16),dp(13),dp(16),dp(13));background=outlined(color(R.color.wb_surface),dp(15),color(R.color.wb_outline));imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_GO;setOnEditorActionListener{_,actionId,_->if(actionId==android.view.inputmethod.EditorInfo.IME_ACTION_GO){runInput();true}else false};contentDescription=t("Command or terminal input")
  }
  page.addView(command,LinearLayout.LayoutParams(-1,-2))
  val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
  val run=actionButton("Run command",color(R.color.wb_primary),color(R.color.wb_on_primary)){runInput()}.apply{textSize=15f;contentDescription=t("Run command or send terminal input")}
  stopButton=actionButton("STOP · Ctrl+C",color(R.color.wb_surface_raised),color(R.color.wb_error)){sendControl(byteArrayOf(3))}.apply{contentDescription=t("Send Ctrl+C to the foreground terminal process")}
  actions.addView(run,LinearLayout.LayoutParams(0,dp(52),1.1f));actions.addView(stopButton,LinearLayout.LayoutParams(0,dp(52),1f).apply{leftMargin=dp(8)})
  val compactActionRow=resources.displayMetrics.widthPixels/resources.displayMetrics.density>=360f&&resources.configuration.fontScale<1.3f
  val keyToggle=actionButton("Keys +",color(R.color.wb_surface_raised),color(R.color.wb_secondary)){}.apply{
   contentDescription=t("Show terminal control keys");isSelected=false
   setOnClickListener{val expanded=terminalKeysPanel.visibility!=View.VISIBLE;terminalKeysPanel.visibility=if(expanded)View.VISIBLE else View.GONE;text=t(if(expanded)"Keys −" else "Keys +");isSelected=expanded;contentDescription=t(if(expanded)"Hide terminal control keys" else "Show terminal control keys");setTextColor(color(if(expanded)R.color.wb_primary else R.color.wb_secondary));terminalKeysPanel.contentDescription=t(if(expanded)"Terminal control keys shown" else "Terminal control keys hidden")}
  }
  if(compactActionRow)actions.addView(keyToggle,LinearLayout.LayoutParams(0,dp(52),1f).apply{leftMargin=dp(8)})
  page.addView(space(6));page.addView(actions)
  if(!compactActionRow)page.addView(keyToggle,LinearLayout.LayoutParams(-1,dp(48)))
  val keys=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
  listOf("Ctrl+C" to byteArrayOf(3),"Ctrl+D" to byteArrayOf(4),"Ctrl+Z" to byteArrayOf(26),"Ctrl+\\" to byteArrayOf(28),"Backspace" to byteArrayOf(127),"Esc" to byteArrayOf(27),"Tab" to byteArrayOf(9),"↑" to "\u001b[A".toByteArray(),"↓" to "\u001b[B".toByteArray(),"←" to "\u001b[D".toByteArray(),"→" to "\u001b[C".toByteArray()).forEach{(key,bytes)->val button=actionButton(key,color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){if(!stream&&key=="↑")moveInputVertical(-1) else if(!stream&&key=="↓")moveInputVertical(1) else if(!stream&&key=="←")moveInputCursor(-1) else if(!stream&&key=="→")moveInputCursor(1) else if(stream)sendControl(bytes)}.apply{contentDescription=t("Terminal key $key")};terminalKeyButtons.add(button to key);keys.addView(button,LinearLayout.LayoutParams(-2,dp(48)).apply{rightMargin=dp(7)})}
  terminalKeysPanel=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;visibility=View.GONE;addView(keys)}
  page.addView(terminalKeysPanel)
  page.addView(space(4))
  val console=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=rounded(color(R.color.wb_surface),dp(18));clipToOutline=true}
  terminalConsole=console
  val compactConsole=language!="ru"&&resources.displayMetrics.widthPixels/resources.displayMetrics.density>=360f&&resources.configuration.fontScale<1.3f
  val consoleBar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(8),0,dp(8),0)}
  consoleBar.addView(TextView(this).apply{text=t("OUTPUT");textSize=10f;letterSpacing=.05f;typeface=Typeface.DEFAULT_BOLD;gravity=android.view.Gravity.CENTER;setTextColor(color(R.color.wb_on_surface_variant));contentDescription=t("Console output")},LinearLayout.LayoutParams(0,dp(48),1f))
  disconnectShellButton=actionButton("Disconnect",color(R.color.wb_surface_raised),color(R.color.wb_error)){closeStream()}.apply{contentDescription=t("Close shell session. This does not send Ctrl+C to the foreground process.")}
  val clearOutputButton=actionButton("Clear",Color.TRANSPARENT,color(R.color.wb_on_surface_variant)){clearOutput()}.apply{contentDescription=t("Clear console output")}
  val copyOutputButton=actionButton("Copy",Color.TRANSPARENT,color(R.color.wb_secondary)){copyAllOutput()}.apply{contentDescription=t("Copy all console output")}
  fullscreenButton=actionButton("⛶",Color.TRANSPARENT,color(R.color.wb_on_surface_variant)){toggleFullscreenTerminal()}.apply{contentDescription=t("Open fullscreen terminal")}
  if(compactConsole){consoleBar.addView(clearOutputButton,LinearLayout.LayoutParams(-2,dp(44)));consoleBar.addView(copyOutputButton,LinearLayout.LayoutParams(-2,dp(44)))}
  consoleBar.addView(fullscreenButton,LinearLayout.LayoutParams(dp(48),dp(44)))
  consoleBar.addView(disconnectShellButton,LinearLayout.LayoutParams(-2,dp(48)))
  console.addView(consoleBar)
  if(!compactConsole){val outputActions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(8),0,dp(8),0)};outputActions.addView(clearOutputButton,LinearLayout.LayoutParams(0,dp(48),1f));outputActions.addView(copyOutputButton,LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(8)});console.addView(outputActions)}
  output=TextView(this).apply{text=t(EMPTY_OUTPUT);setTextIsSelectable(true);textSize=13f;typeface=Typeface.MONOSPACE;setTextColor(color(R.color.wb_on_surface));setPadding(dp(14),dp(10),dp(14),dp(16));contentDescription=t("Terminal output; no command output yet")}
  outputScroll=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=true;setBackgroundColor(color(R.color.wb_console));addView(output)}
  console.addView(outputScroll,LinearLayout.LayoutParams(-1,0,1f).apply{height=0})
  page.addView(console,LinearLayout.LayoutParams(-1,if(isLandscape())dp(360) else 0,if(isLandscape())0f else 1f).apply{topMargin=dp(10)})
  refreshModeButtons()
  return page
 }
 private fun toggleFullscreenTerminal(){if(fullscreenTerminal)exitFullscreenTerminal() else enterFullscreenTerminal()}
 private fun enterFullscreenTerminal(){
  if(currentDestination!=0)return
  fullscreenTerminal=true
  val dialog=Dialog(this)
  fullscreenDialog=dialog
  val shell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(4),dp(10),dp(4));setBackgroundColor(color(R.color.wb_canvas))}
  val bar=LinearLayout(this).apply{orientation=0;gravity=android.view.Gravity.CENTER_VERTICAL}
  bar.addView(TextView(this).apply{text=t("TERMINAL");textSize=12f;letterSpacing=.08f;typeface=Typeface.DEFAULT_BOLD;setTextColor(color(R.color.wb_primary))},LinearLayout.LayoutParams(0,dp(44),1f))
  bar.addView(actionButton("Copy",Color.TRANSPARENT,color(R.color.wb_secondary)){if(stream)copyFullscreenSelectionOrTranscript() else copyAllOutput()},LinearLayout.LayoutParams(-2,dp(44)))
  bar.addView(actionButton("Clear",Color.TRANSPARENT,color(R.color.wb_on_surface_variant)){clearOutput()}.apply{contentDescription=t("Clear terminal display")},LinearLayout.LayoutParams(-2,dp(44)))
  bar.addView(actionButton("Exit",color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){exitFullscreenTerminal()},LinearLayout.LayoutParams(-2,dp(44)))
  shell.addView(bar)
  val terminalFrame=FrameLayout(this).apply{setBackgroundColor(color(R.color.wb_console));isFocusable=true;isFocusableInTouchMode=true};fullscreenFrame=terminalFrame
  val textFallback=TextView(this).apply{text=output.text;setTextIsSelectable(true);textSize=terminalFontSizeSp.toFloat();typeface=Typeface.MONOSPACE;setTextColor(color(R.color.wb_on_surface));setPadding(dp(14),dp(12),dp(14),dp(16));contentDescription=t("Selectable terminal output")};fullscreenPlainOutput=textFallback
  val fallbackScroll=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=true;addView(textFallback)}
  terminalFrame.addView(fallbackScroll,FrameLayout.LayoutParams(-1,-1))
  shell.addView(terminalFrame,LinearLayout.LayoutParams(-1,0,1f))
  terminalKeysExpanded=false;terminalCtrlLatched=false
  val keysToggle=actionButton("Keys +",color(R.color.wb_surface_raised),color(R.color.wb_secondary)){}.apply{contentDescription=t("Show terminal control keys")}
  keysToggle.setOnClickListener{terminalKeysExpanded=!terminalKeysExpanded;terminalFullscreenKeys?.visibility=if(terminalKeysExpanded)View.VISIBLE else View.GONE;keysToggle.text=t(if(terminalKeysExpanded)"Keys −" else "Keys +")}
  shell.addView(keysToggle,LinearLayout.LayoutParams(-1,dp(44)))
  val keyRow=LinearLayout(this).apply{orientation=0}
  val control=actionButton("Ctrl",color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){}.apply{contentDescription=t("Toggle Control modifier")}
  terminalCtrlButton=control
  control.setOnClickListener{terminalCtrlLatched=!terminalCtrlLatched;refreshCtrlButton()}
  keyRow.addView(control,LinearLayout.LayoutParams(dp(52),dp(48)))
  listOf("Esc" to byteArrayOf(27),"Tab" to byteArrayOf(9),"↑" to "\u001b[A".toByteArray(),"↓" to "\u001b[B".toByteArray(),"←" to "\u001b[D".toByteArray(),"→" to "\u001b[C".toByteArray()).forEach{(label,bytes)->keyRow.addView(actionButton(label,color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){if(label in setOf("↑","↓","←","→"))sendFullscreenArrow(label,bytes) else sendControl(bytes);if(terminalCtrlLatched){terminalCtrlLatched=false;refreshCtrlButton()}},LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(3);rightMargin=dp(3)})}
  terminalFullscreenKeys=keyRow
  shell.addView(keyRow,LinearLayout.LayoutParams(-1,dp(48)));keyRow.visibility=View.GONE
  if(!stream){
   val input=EditText(this).apply{hint=t("Enter a command");minLines=1;maxLines=3;setText(command.text);setSingleLine(false);textSize=15f;setTextColor(color(R.color.wb_on_surface));setHintTextColor(color(R.color.wb_on_surface_variant));setPadding(dp(12),dp(8),dp(12),dp(8));background=outlined(color(R.color.wb_surface),dp(14),color(R.color.wb_outline));imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_GO;setOnEditorActionListener{_,actionId,_->if(actionId==android.view.inputmethod.EditorInfo.IME_ACTION_GO){command.setText(text);runInput();setText("");true}else false}}
   shell.addView(input,LinearLayout.LayoutParams(-1,-2))
   shell.addView(actionButton("Run command",color(R.color.wb_primary),color(R.color.wb_on_primary)){command.setText(input.text);runInput();input.setText("");textFallback.text=output.text;fallbackScroll.post{fallbackScroll.fullScroll(View.FOCUS_DOWN)}},LinearLayout.LayoutParams(-1,dp(48)))
  }
  dialog.setContentView(shell)
  dialog.setOnDismissListener{fullscreenTerminal=false;fullscreenDialog=null;fullscreenFrame=null;fullscreenPlainOutput=null;terminalCtrlButton=null;fullscreenButton.text=t("⛶");fullscreenButton.contentDescription=t("Open fullscreen terminal");terminalCtrlLatched=false}
  dialog.window?.apply{setBackgroundDrawable(ColorDrawable(color(R.color.wb_canvas)));setLayout(-1,-1);setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)}
  dialog.show();dialog.window?.setLayout(-1,-1)
  fullscreenButton.text=t("⤢");fullscreenButton.contentDescription=t("Exit fullscreen terminal")
  if(stream&&streamReady)attachFullscreenEmulator(terminalFrame)
 }
 private var terminalFullscreenKeys:View?=null
 private var terminalCtrlLatched=false
 private var terminalCtrlButton:Button?=null
 private fun attachFullscreenEmulator(frame:FrameLayout){
  if(!fullscreenTerminal||!streamReady)return
  val view=fullscreenView?:BridgeTerminalView(this,{ bytes->sendTerminalBytes(bytes) },{ rows,cols->notifyWatchPtySize(rows,cols) },terminalFontSizeSp.toFloat()).also{created->
   fullscreenView=created
  }
  (view.parent as? android.view.ViewGroup)?.removeView(view)
  frame.removeAllViews()
  frame.addView(view,FrameLayout.LayoutParams(-1,-1))
  view.post{
   if(!fullscreenTerminal||fullscreenView!==view||!view.isAttachedToWindow)return@post
   view.requestFocus()
   val imm=getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
   imm.restartInput(view)
   imm.showSoftInput(view,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
   view.postDelayed({if(fullscreenTerminal&&fullscreenView===view&&view.hasWindowFocus()&&view.hasFocus())imm.showSoftInput(view,android.view.inputmethod.InputMethodManager.SHOW_FORCED)},120L)
  }
 }
 private fun notifyWatchPtySize(rows:Int,cols:Int){
  val target=nodeId?:return
  if(!streamReady)return
  val data=JSONObject().put("rows",rows).put("columns",cols).toString().toByteArray()
  scope.launch{try{Wearable.getMessageClient(this@MainActivity).sendMessage(target,"/terminal/resize",data).await()}catch(_:Exception){}}
 }
 private fun sendFullscreenArrow(label:String,fallback:ByteArray){sendControl(fallback)}
 private fun refreshCtrlButton(){terminalCtrlButton?.let{button->button.background=rounded(color(if(terminalCtrlLatched)R.color.wb_primary else R.color.wb_surface_raised),dp(13));button.setTextColor(color(if(terminalCtrlLatched)R.color.wb_on_primary else R.color.wb_on_surface))}}
 private fun sendTerminalBytes(bytes:ByteArray){
  val value=if(terminalCtrlLatched && bytes.size==1){val b=bytes[0].toInt() and 255;terminalCtrlLatched=false;refreshCtrlButton();when(b){in 64..95->byteArrayOf((b and 31).toByte());in 97..122->byteArrayOf((b-96).toByte());else->bytes}} else bytes
  sendControl(value)
 }
 private fun copyTerminalText(text:String){val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;clipboard.setPrimaryClip(ClipData.newPlainText("WearBridge terminal",text));Toast.makeText(this,t("Terminal selection copied"),Toast.LENGTH_SHORT).show()}
 private fun copyFullscreenSelectionOrTranscript(){val selected=fullscreenView?.selectedText().orEmpty();if(selected.isNotBlank())copyTerminalText(selected) else copyAllOutput()}
 private fun exitFullscreenTerminal(){fullscreenDialog?.dismiss()}
 private fun stopLocalTerminal(){fullscreenView=null;terminalFullscreenKeys=null;terminalCtrlLatched=false}
 private fun buildFilesPage():View{
  val page=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(5),dp(20),dp(20));setBackgroundColor(color(R.color.wb_canvas))}
  page.addView(TextView(this).apply{text=t("Watch files");textSize=28f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setTextColor(color(R.color.wb_on_surface))})
  page.addView(copyText("Browse storage, move files, or remove selected items.",13f))
  page.addView(label("WATCH STORAGE"))
  page.addView(actionButton("Browse & download",color(R.color.wb_primary),color(R.color.wb_on_primary)){rootBrowserConfirmed=false;browserUploadSources=emptyList();browserUploadIsFolder=false;browserUploadFolders=emptyList();selectedRemotePaths.clear();openRemoteBrowser("/sdcard")},LinearLayout.LayoutParams(-1,dp(54)))
  page.addView(space(12))
  page.addView(label("SEND TO WATCH"))
  page.addView(actionButton("Choose files",color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){rootBrowserConfirmed=false;pickUploadFiles()},LinearLayout.LayoutParams(-1,dp(52)))
  page.addView(space(8))
  page.addView(actionButton("Choose a folder",color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){rootBrowserConfirmed=false;pickUploadFolder()},LinearLayout.LayoutParams(-1,dp(52)))
  page.addView(space(18))
  page.addView(copyText("Downloads use the selected phone location. Upload and download keep folder structure for batches. ROOT access is controlled in Settings.",13f))
  return page
 }
 private fun buildSettingsPage():View{
  val page=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(5),dp(20),dp(24));setBackgroundColor(color(R.color.wb_canvas))}
  page.addView(TextView(this).apply{text=t("Settings");textSize=28f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setTextColor(color(R.color.wb_on_surface))})
  page.addView(copyText("Choose how watch commands run and manage the local client API.",13f))
  page.addView(label("LANGUAGE"))
  page.addView(actionButton(if(language=="ru")"Русский  ·  English" else "English  ·  Русский",color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){setLanguage(if(language=="ru")"en" else "ru")},LinearLayout.LayoutParams(-1,dp(52)))
  page.addView(label("TERMINAL FONT"))
  val fontValue=TextView(this).apply{text="${t("Terminal font size")} · $terminalFontSizeSp sp";textSize=14f;setTextColor(color(R.color.wb_on_surface));setPadding(dp(4),0,dp(4),0)}
  page.addView(fontValue)
  page.addView(SeekBar(this).apply{
   max=10;progress=terminalFontSizeSp-12;contentDescription=t("Terminal font size")
   progressTintList=android.content.res.ColorStateList.valueOf(color(R.color.wb_primary))
   thumbTintList=android.content.res.ColorStateList.valueOf(color(R.color.wb_primary))
   setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
    override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean){
     if(!fromUser)return
     terminalFontSizeSp=value+12
     fontValue.text="${t("Terminal font size")} · $terminalFontSizeSp sp"
     fullscreenView?.setFontSizeSp(terminalFontSizeSp.toFloat())
     fullscreenPlainOutput?.textSize=terminalFontSizeSp.toFloat()
     getSharedPreferences("wearbridge_ui",MODE_PRIVATE).edit().putInt("terminal_font_sp",terminalFontSizeSp).apply()
    }
    override fun onStartTrackingTouch(bar:SeekBar?){}
    override fun onStopTrackingTouch(bar:SeekBar?){}
   })
  },LinearLayout.LayoutParams(-1,dp(48)))
  page.addView(label("WATCH EXECUTION PROFILE"))
  privilegeButton=actionButton("",color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){rootMode=!rootMode;getSharedPreferences(TermuxApiService.PREFS,MODE_PRIVATE).edit().putBoolean("root_mode",rootMode).apply();refreshPrivilegeButton();append("Execution profile: ${if(rootMode) "ROOT (requires watch su approval)" else "SHELL/current Shizuku identity"}\n")}.apply{contentDescription=t("Toggle watch execution profile between SHELL and ROOT")}
  refreshPrivilegeButton();page.addView(privilegeButton,LinearLayout.LayoutParams(-1,dp(52)))
  page.addView(copyText("SHELL uses the Shizuku identity. ROOT uses the watch's separately granted KernelSU access.",12f))
  page.addView(label("TERMUX API"))
  apiButton=actionButton("",color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){toggleTermuxApi()}.apply{contentDescription=t("Start or stop the Termux loopback API")}
  refreshApiButton();page.addView(apiButton,LinearLayout.LayoutParams(-1,dp(52)))
  page.addView(actionButton(t("Copy API token"),color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){copyTermuxApiToken()},LinearLayout.LayoutParams(-1,dp(48)))
  page.addView(copyText("The API token grants command access to this phone's WearBridge. Keep it private.",12f))
  page.addView(copyText("The API is available only on this phone at 127.0.0.1:${TermuxApiService.PORT}; it is not exposed to the local network.",12f))
  page.addView(label("CONNECTION"))
  val connectionCard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14));background=rounded(color(R.color.wb_surface),dp(16))}
  connectionCard.addView(TextView(this).apply{text=t("Wear Data Layer over the existing watch pairing");textSize=15f;typeface=Typeface.DEFAULT_BOLD;setTextColor(color(R.color.wb_on_surface))})
  connectionCard.addView(copyText("Commands and terminal bytes use the paired connection. Wi-Fi is used only for direct file bytes when available.",12f))
  page.addView(connectionCard)
  return page
 }
 private fun enableEdgeToEdge(root:View){
  window.statusBarColor=Color.TRANSPARENT;window.navigationBarColor=Color.TRANSPARENT
  @Suppress("DEPRECATION")
  run{window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION}
  root.setOnApplyWindowInsetsListener{view,insets->
   var left=insets.systemWindowInsetLeft;var top=insets.systemWindowInsetTop;var right=insets.systemWindowInsetRight;var bottom=insets.systemWindowInsetBottom
   if(android.os.Build.VERSION.SDK_INT>=28){val cutout=insets.displayCutout;if(cutout!=null){left=maxOf(left,cutout.safeInsetLeft);top=maxOf(top,cutout.safeInsetTop);right=maxOf(right,cutout.safeInsetRight);bottom=maxOf(bottom,cutout.safeInsetBottom)}}
   view.setPadding(left,top,right,bottom);insets
  }
  root.requestApplyInsets()
 }
 private suspend fun refreshConnection(){
  try{nodeId=Wearable.getNodeClient(this).connectedNodes.await().firstOrNull()?.id
   val connected=nodeId!=null
   connectionStatus.text=t(if(connected)"WATCH CONNECTED" else "WATCH OFFLINE")
   connectionStatus.setTextColor(color(if(connected)R.color.wb_success else R.color.wb_warning))
   refreshModeStatus()
  }catch(t:Throwable){nodeId=null;connectionStatus.text=t("CONNECTION ERROR");connectionStatus.setTextColor(color(R.color.wb_error));mode.text=t("Data Layer unavailable · ${t.message?:"check Wear OS connection"}")}
 }
 private fun actionButton(label:String,bg:Int,fg:Int,onClick:()->Unit)=Button(this).apply{
  text=t(label);textSize=13f;isAllCaps=false;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setTextColor(fg)
  background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(color(R.color.wb_outline)),rounded(bg,dp(15)),rounded(Color.WHITE,dp(15)))
  setPadding(dp(12),0,dp(12),0);minHeight=dp(48);minimumHeight=dp(48);minWidth=dp(48);minimumWidth=dp(48);setOnClickListener{onClick()}
 }
 private fun refreshModeButtons(){
  val active=if(stream)streamModeButton else normalModeButton
  val inactive=if(stream)normalModeButton else streamModeButton
  active.background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(color(R.color.wb_outline)),rounded(color(R.color.wb_primary),dp(15)),rounded(Color.WHITE,dp(15)));active.setTextColor(color(R.color.wb_on_primary));active.isSelected=true
  inactive.background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(color(R.color.wb_outline)),rounded(Color.TRANSPARENT,dp(15)),rounded(Color.WHITE,dp(15)));inactive.setTextColor(color(R.color.wb_on_surface_variant));inactive.isSelected=false
  if(::stopButton.isInitialized){stopButton.isEnabled=streamReady;stopButton.alpha=if(streamReady)1f else .46f}
  if(::disconnectShellButton.isInitialized){disconnectShellButton.isEnabled=streamReady;disconnectShellButton.alpha=if(streamReady)1f else .46f}
  terminalKeyButtons.forEach{(button,key)->val available=stream||key=="↑"||key=="↓"||key=="←"||key=="→";button.isEnabled=available;button.alpha=if(available)1f else .42f}
 }
 private fun copyAllOutput(){
  if(!hasOutput){Toast.makeText(this,t("Terminal output is empty"),Toast.LENGTH_SHORT).show();return}
  val content=output.text.toString()
  if(content.isEmpty()){Toast.makeText(this,t("Terminal output is empty"),Toast.LENGTH_SHORT).show();return}
  val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  clipboard.setPrimaryClip(ClipData.newPlainText("WearBridge terminal output",content))
  Toast.makeText(this,t("Terminal output copied"),Toast.LENGTH_SHORT).show()
 }
 private fun clearOutput(){hasOutput=false;output.text=t(EMPTY_OUTPUT);fullscreenPlainOutput?.text=t(EMPTY_OUTPUT);fullscreenView?.clearScreen();output.contentDescription=t("Terminal output; no command output yet")}
 private fun refreshPrivilegeButton(){privilegeButton.text=t(if(rootMode)"ROOT · ON" else "SHELL · ACTIVE");privilegeButton.background=rounded(if(rootMode)color(R.color.wb_error) else color(R.color.wb_surface_raised),dp(15));privilegeButton.setTextColor(if(rootMode)color(R.color.wb_on_primary) else color(R.color.wb_on_surface));privilegeButton.isSelected=rootMode}
 private fun copyTermuxApiToken(){
  val file=java.io.File(filesDir,TermuxApiService.TOKEN_FILE)
  if(!file.isFile){Toast.makeText(this,t("Start the API once before copying its token."),Toast.LENGTH_SHORT).show();return}
  val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  clipboard.setPrimaryClip(ClipData.newPlainText("WearBridge Termux API token",file.readText().trim()))
  Toast.makeText(this,t("API token copied. Save it in Termux with wadb configure."),Toast.LENGTH_LONG).show()
 }
 private fun refreshApiButton(){val on=getSharedPreferences(TermuxApiService.PREFS,MODE_PRIVATE).getBoolean("api_running",false);apiButton.text=t(if(on)"TERMUX API · RUNNING" else "TERMUX API · STOPPED");apiButton.background=rounded(if(on)color(R.color.wb_success) else color(R.color.wb_surface_raised),dp(15));apiButton.setTextColor(if(on)color(R.color.wb_on_primary) else color(R.color.wb_on_surface));apiButton.isSelected=on}
 private fun toggleTermuxApi(){
  val prefs=getSharedPreferences(TermuxApiService.PREFS,MODE_PRIVATE)
  val enabled=prefs.getBoolean("api_running",false)
  if(enabled){stopService(android.content.Intent(this,TermuxApiService::class.java));prefs.edit().putBoolean("api_running",false).apply();append("Termux API stopped\n")}
  else{try{val intent=android.content.Intent(this,TermuxApiService::class.java);prefs.edit().putBoolean("api_running",true).apply();if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(intent) else startService(intent);append("Termux API starting at 127.0.0.1:${TermuxApiService.PORT}\n")}
   catch(t:Throwable){prefs.edit().putBoolean("api_running",false).apply();append("Could not start Termux API: ${t.message}\n")}}
  refreshApiButton()
 }
 private fun refreshModeStatus(){if(!::mode.isInitialized)return;mode.text=t(when{nodeId==null->"Waiting for paired watch";streamReady->"STREAM · ${if(rootMode)"ROOT" else "SHELL"} PTY connected";stream->"STREAM · connecting…";pendingNormalRequests.isNotEmpty()->"NORMAL · ${pendingNormalRequests.size} command(s) running";else->"NORMAL · ready"})}
 private fun setNormal(){ if(stream)closeStream();stream=false;refreshModeStatus();refreshModeButtons() }
 private fun setStream(){ if(stream)return;stream=true;val attempt=++streamAttempt;refreshModeButtons();mode.text=t("STREAM · connecting…");val target=nodeId?:run{append("Watch disconnected\n");stream=false;refreshModeButtons();refreshModeStatus();return}
  scope.launch{try{val cc=Wearable.getChannelClient(this@MainActivity);val ch=cc.openChannel(target,if(rootMode)"/terminal/root" else "/terminal").await()?:error("Could not open channel");if(!stream||attempt!=streamAttempt){cc.close(ch).await();return@launch};channel=ch;channelInput=cc.getInputStream(ch).await();channelOutput=cc.getOutputStream(ch).await();if(!stream||attempt!=streamAttempt)return@launch;streamReady=true;refreshModeButtons();refreshModeStatus();append("\n[stream connected · ${if(rootMode) "root" else "shell"}]\n");if(fullscreenView==null)fullscreenView=BridgeTerminalView(this@MainActivity,{ bytes->sendTerminalBytes(bytes) },{ rows,cols->notifyWatchPtySize(rows,cols) },terminalFontSizeSp.toFloat());if(fullscreenTerminal)fullscreenFrame?.let{attachFullscreenEmulator(it)};val queued=pendingInput.toList();pendingInput.clear();pendingBytes=0;queued.forEach{sendControl(it)}
   launch(Dispatchers.IO){val b=ByteArray(4096);try{while(isActive){val n=channelInput?.read(b)?:-1;if(n<0){withContext(Dispatchers.Main){if(streamReady)markStreamLost("remote closed the channel")};break};withContext(Dispatchers.Main){fullscreenView?.feed(b,n)};val text=String(b,0,n,Charsets.UTF_8);withContext(Dispatchers.Main){append(text)}}}catch(e:Exception){withContext(Dispatchers.Main){if(streamReady){streamReady=false;stream=false;mode.text=t("STREAM · disconnected");markStreamLost("${e.message}")}}} }
  }catch(e:Exception){if(attempt!=streamAttempt)return@launch;append("Stream open failed: ${e.message}\n");stream=false;streamReady=false;pendingInput.clear();pendingBytes=0;refreshModeButtons();mode.text=t("STREAM · disconnected")}}
 }
 private fun closeStream(){streamAttempt++;val ch=channel;channel=null;streamReady=false;pendingInput.clear();pendingBytes=0;try{channelInput?.close()}catch(_:Exception){};try{channelOutput?.close()}catch(_:Exception){};channelInput=null;channelOutput=null;stopLocalTerminal();if(ch!=null)scope.launch{try{Wearable.getChannelClient(this@MainActivity).close(ch).await()}catch(_:Exception){}};stream=false;refreshModeButtons();mode.text=t(if(nodeId==null)"Waiting for paired watch" else "NORMAL · terminal closed");append("\n[terminal disconnected]\n")}
 private fun runInput(){val text=command.text.toString();if(text.isEmpty()&&!stream)return;command.setText("")
  if(stream){val bytes=(text+"\r").toByteArray(Charsets.UTF_8);sendControl(bytes);return}
  if(commandHistory.lastOrNull()!=text)commandHistory.add(text)
  if(commandHistory.size>200)commandHistory.removeAt(0)
  historyIndex=commandHistory.size;historyDraft=""
  val target=nodeId?:run{append("Watch disconnected\n");return};val id=System.currentTimeMillis().toString()
  pendingNormalRequests.add(id);refreshModeStatus()
  scope.launch{try{Wearable.getMessageClient(this@MainActivity).sendMessage(target,"/exec","$id\n${if(rootMode) "root" else "shell"}\n$text".toByteArray()).await()}catch(e:Exception){pendingNormalRequests.remove(id);refreshModeStatus();append("Transport error: ${e.message}\n")}}
 }
 private fun navigateCommandHistory(direction:Int){
  if(commandHistory.isEmpty())return
  if(historyIndex==commandHistory.size)historyDraft=command.text.toString()
  historyIndex=(historyIndex+direction).coerceIn(0,commandHistory.size)
  command.setText(if(historyIndex==commandHistory.size)historyDraft else commandHistory[historyIndex])
  command.setSelection(command.text.length)
 }
 private fun moveInputCursor(direction:Int){
  val text=command.text ?: return
  val start=command.selectionStart.coerceAtLeast(0);val end=command.selectionEnd.coerceAtLeast(0)
  if(direction<0){command.setSelection(if(start!=end)minOf(start,end) else (start-1).coerceAtLeast(0));return}
  command.setSelection(if(start!=end)maxOf(start,end) else (end+1).coerceAtMost(text.length))
 }
 private fun moveInputVertical(direction:Int){
  val text=command.text ?: return
  if(!command.hasFocus()){navigateCommandHistory(direction);return}
  if(!text.contains('\n')){val cursor=command.selectionStart;if(direction<0&&cursor==0)navigateCommandHistory(-1) else if(direction>0&&cursor==text.length)navigateCommandHistory(1);return}
  val cursor=command.selectionStart.coerceAtLeast(0);val lineStart=(text.lastIndexOf('\n',(cursor-1).coerceAtLeast(0))+1).coerceAtMost(cursor)
  val column=cursor-lineStart
  if(direction<0){if(lineStart==0)return;val previousEnd=lineStart-1;val previousStart=text.lastIndexOf('\n',(previousEnd-1).coerceAtLeast(0))+1;command.setSelection(previousStart+column.coerceAtMost(previousEnd-previousStart))}
  else{val currentEnd=text.indexOf('\n',cursor).let{if(it<0)text.length else it};if(currentEnd>=text.length)return;val nextStart=currentEnd+1;val nextEnd=text.indexOf('\n',nextStart).let{if(it<0)text.length else it};command.setSelection(nextStart+column.coerceAtMost(nextEnd-nextStart))}
 }
 private fun sendControl(bytes:ByteArray){
  if(!stream)return
  val out=channelOutput
  if(!streamReady||out==null){if(pendingBytes+bytes.size>65536){append("Stream is connecting; input queue is full\n");return};pendingInput.add(bytes.copyOf());pendingBytes+=bytes.size;return}
  scope.launch(writes){try{writeMutex.withLock{out.write(bytes);out.flush()}}catch(e:Exception){withContext(Dispatchers.Main){streamReady=false;stream=false;refreshModeButtons();mode.text=t("STREAM · disconnected");markStreamLost("write failed: ${e.message}")}}}
 }
 override fun dispatchKeyEvent(event:KeyEvent):Boolean{
  if(fullscreenTerminal&&stream)return super.dispatchKeyEvent(event)
  if(!stream&&event.action==KeyEvent.ACTION_DOWN&&event.keyCode==KeyEvent.KEYCODE_DPAD_UP&&!command.hasFocus()){navigateCommandHistory(-1);return true}
  if(!stream&&event.action==KeyEvent.ACTION_DOWN&&event.keyCode==KeyEvent.KEYCODE_DPAD_DOWN&&!command.hasFocus()){navigateCommandHistory(1);return true}
  if(stream&&event.action==KeyEvent.ACTION_DOWN){val code=when{event.isCtrlPressed&&event.keyCode==KeyEvent.KEYCODE_C->byteArrayOf(3);event.isCtrlPressed&&event.keyCode==KeyEvent.KEYCODE_D->byteArrayOf(4);event.isCtrlPressed&&event.keyCode==KeyEvent.KEYCODE_Z->byteArrayOf(26);event.isCtrlPressed&&event.keyCode==KeyEvent.KEYCODE_BACKSLASH->byteArrayOf(28);event.keyCode==KeyEvent.KEYCODE_TAB->byteArrayOf(9);event.keyCode==KeyEvent.KEYCODE_DEL&&command.text.isEmpty()->byteArrayOf(127);event.keyCode==KeyEvent.KEYCODE_ESCAPE->byteArrayOf(27);event.keyCode==KeyEvent.KEYCODE_DPAD_UP->"\u001b[A".toByteArray();event.keyCode==KeyEvent.KEYCODE_DPAD_DOWN->"\u001b[B".toByteArray();event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT->"\u001b[D".toByteArray();event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT->"\u001b[C".toByteArray();event.keyCode==KeyEvent.KEYCODE_ENTER->byteArrayOf(13);else->null};if(code!=null){if(event.keyCode==KeyEvent.KEYCODE_ENTER){runInput()}else sendControl(code);return true}}
  return super.dispatchKeyEvent(event)
 }
 override fun onResume(){super.onResume();refreshApiButton();if(::connectionStatus.isInitialized&&::mode.isInitialized)scope.launch{refreshConnection()}}
 override fun onMessageReceived(event:MessageEvent){
  val message=String(event.data,Charsets.UTF_8)
  if(event.path=="/file-transfer/list-result"||event.path=="/file-transfer/mkdir-result"||event.path=="/file-transfer/delete-result"){
   val json=try{JSONObject(message)}catch(_:Throwable){null};val id=json?.optString("id").orEmpty()
   val waiters=when { event.path.endsWith("list-result")->listingWaiters; event.path.endsWith("mkdir-result")->directoryWaiters; else->deleteWaiters }
   val waiter=waiters.remove(id)
   if(waiter!=null&&json!=null){waiter.complete(json);return}
  }
  runOnUiThread{when(event.path){"/result"->showNormalResult(message);"/stream-error"->append("Watch stream setup failed: $message\n");"/file-transfer/ready"->handleWifiFileReady(message);"/file-transfer/list-result"->handleRemoteListing(message)}}
 }

 private fun showNormalResult(message:String){
  pendingNormalRequests.remove(message.substringBefore('\n'))
  refreshModeStatus()
  val payload=message.substringAfter('\n',message)
  val stdoutMarker="stdout:\n";val stderrMarker="\nstderr:\n"
  val start=payload.indexOf(stdoutMarker)
  if(start<0){append(payload);return}
  val content=payload.substring(start+stdoutMarker.length)
  val split=content.indexOf(stderrMarker)
  val stdout=if(split>=0)content.substring(0,split) else content
  val stderr=if(split>=0)content.substring(split+stderrMarker.length) else ""
  append(stdout+stderr)
 }

 private fun showFileActions(){
  val panel=LinearLayout(this).apply{orientation=1;setPadding(dp(18),dp(8),dp(18),dp(8))}
  panel.addView(TextView(this).apply{text=t("Select multiple files or folders. Folder transfers keep their directory structure.");textSize=14f;setTextColor(color(R.color.wb_on_surface_variant));setPadding(0,0,0,dp(8))})
  lateinit var dialog:AlertDialog
  panel.addView(actionButton("DOWNLOAD FILES / FOLDERS",color(R.color.wb_primary),color(R.color.wb_on_primary)){dialog.dismiss();rootBrowserConfirmed=false;browserUploadSources=emptyList();browserUploadIsFolder=false;browserUploadFolders=emptyList();selectedRemotePaths.clear();openRemoteBrowser("/sdcard")},LinearLayout.LayoutParams(-1,dp(48)))
  panel.addView(actionButton("UPLOAD FILES",color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){dialog.dismiss();rootBrowserConfirmed=false;pickUploadFiles()},LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)})
  panel.addView(actionButton("UPLOAD FOLDER",color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){dialog.dismiss();rootBrowserConfirmed=false;pickUploadFolder()},LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)})
  dialog=AlertDialog.Builder(this).setTitle(t("Files on Xiaomi Watch 5")).setView(panel).setNegativeButton(t("Close"),null).create();dialog.show()
 }

 private fun pickUploadFiles(){
  val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
  startActivityForResult(intent,REQUEST_UPLOAD)
 }

 private fun pickUploadFolder(){
  startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),REQUEST_UPLOAD_TREE)
 }

 private fun openRemoteBrowser(path:String){
  if(fileBatchActive||pendingFileTransfer!=null){Toast.makeText(this,t("Wait for the active transfer"),Toast.LENGTH_SHORT).show();return}
  browserPath=path;browserOffset=0;browserPage=0;remoteEntries.clear();browserRequestId=java.util.UUID.randomUUID().toString();pendingFileRoot=rootMode
  val action={Toast.makeText(this,t("Loading folder…"),Toast.LENGTH_SHORT).show();requestRemoteListing(path,0)}
  if(rootMode&&!rootBrowserConfirmed)confirmRootFileAction("Browse $path") { rootBrowserConfirmed=true;action() } else action()
 }

 private fun requestRemoteListing(path:String,offset:Int){
  val target=nodeId?:run{append("Watch not connected in Wear Data Layer\n");return}
  browserPath=path;browserOffset=offset;val id=java.util.UUID.randomUUID().toString();browserRequestId=id
  Toast.makeText(this,t("Loading $path…"),Toast.LENGTH_SHORT).show()
  val request=JSONObject().put("id",id).put("path",path).put("offset",offset).put("root",rootMode)
  scope.launch{try{Wearable.getMessageClient(this@MainActivity).sendMessage(target,"/file-transfer/list",request.toString().toByteArray()).await()}catch(t:Throwable){append("Could not list watch folder: ${t.message}\n")}}
 }

 private suspend fun requestRemotePage(path:String,offset:Int,root:Boolean):JSONObject{
  val target=nodeId?:throw IOException("Watch not connected in Wear Data Layer")
  val id=java.util.UUID.randomUUID().toString();val waiter=CompletableDeferred<JSONObject>();listingWaiters[id]=waiter
  try{
   val request=JSONObject().put("id",id).put("path",path).put("offset",offset).put("root",root)
   Wearable.getMessageClient(this@MainActivity).sendMessage(target,"/file-transfer/list",request.toString().toByteArray()).await()
   return withTimeout(45_000){waiter.await()}
  }finally{listingWaiters.remove(id)}
 }

 private suspend fun listRemoteDirectory(path:String,root:Boolean):List<RemoteEntry>{
  val result=ArrayList<RemoteEntry>();var offset=0
  while(true){
   val response=requestRemotePage(path,offset,root);val error=response.optString("error")
   if(error.isNotBlank())throw IOException("Cannot list $path: $error")
   val entries=response.optJSONArray("entries")?:org.json.JSONArray()
   for(i in 0 until entries.length())try{val item=entries.getJSONObject(i);result.add(RemoteEntry(item.getString("name"),item.getBoolean("directory"),item.optLong("size",0),item.optLong("modified",0)))}catch(_:Throwable){}
   if(!response.optBoolean("hasMore",false))return result
   offset=result.size
   if(offset>100_000)throw IOException("Folder listing exceeded safety limit")
  }
 }

 private suspend fun collectRemoteFiles(path:String,relative:String,isDirectory:Boolean,root:Boolean,out:MutableList<DownloadSource>,directories:MutableSet<String>,visited:MutableSet<String>,depth:Int=0){
  if(out.size>5000)throw IOException("Folder contains more than 5000 files")
  if(!isDirectory){out.add(DownloadSource(path,relative));return}
  if(depth>=32||!visited.add(path))return
  directories.add(relative)
  val children=listRemoteDirectory(path,root)
  for(entry in children){
   val child=if(path=="/")"/${entry.name}" else "$path/${entry.name}"
   val rel="$relative/${entry.name}"
   if(entry.directory)collectRemoteFiles(child,rel,true,root,out,directories,visited,depth+1)
   else out.add(DownloadSource(child,rel))
   if(out.size>5000)throw IOException("Folder contains more than 5000 files")
  }
 }

 private suspend fun ensureWatchDirectory(path:String,root:Boolean){
  val target=nodeId?:throw IOException("Watch not connected in Wear Data Layer")
  val id=java.util.UUID.randomUUID().toString();val waiter=CompletableDeferred<JSONObject>();directoryWaiters[id]=waiter
  try{
   val request=JSONObject().put("id",id).put("path",path).put("root",root)
   Wearable.getMessageClient(this@MainActivity).sendMessage(target,"/file-transfer/mkdir",request.toString().toByteArray()).await()
   val response=withTimeout(45_000){waiter.await()}
   if(!response.optBoolean("ok",false))throw IOException(response.optString("error","Could not create watch folder $path"))
  }finally{directoryWaiters.remove(id)}
 }

 private fun handleRemoteListing(json:String){
  val response=try{JSONObject(json)}catch(_:Throwable){append("Invalid watch folder listing\n");return}
  if(response.optString("id")!=browserRequestId)return
  val error=response.optString("error");if(error.isNotBlank()){remoteEntries.clear();Toast.makeText(this,t("Cannot browse ${browserPath}: $error"),Toast.LENGTH_LONG).show();return}
  val array=response.optJSONArray("entries")?:org.json.JSONArray()
  for(idx in 0 until array.length())try{val item=array.getJSONObject(idx);remoteEntries.add(RemoteEntry(item.getString("name"),item.getBoolean("directory"),item.optLong("size",0),item.optLong("modified",0)))}catch(_:Throwable){}
  if(response.optBoolean("hasMore",false)){requestRemoteListing(browserPath,remoteEntries.size);return}
  showRemoteListing(browserPath)
 }

 private fun showRemoteListing(path:String){
  val collator=java.text.Collator.getInstance()
  val sorted=remoteEntries.sortedWith(Comparator{a,b->
   if(a.directory!=b.directory)if(a.directory)-1 else 1 else when(browserSort){
    1->b.modifiedEpoch.compareTo(a.modifiedEpoch).takeIf{it!=0}?:collator.compare(a.name,b.name)
    2->b.size.compareTo(a.size).takeIf{it!=0}?:collator.compare(a.name,b.name)
    3->a.name.substringAfterLast('.',"").compareTo(b.name.substringAfterLast('.',""),ignoreCase=true).takeIf{it!=0}?:collator.compare(a.name,b.name)
    else->collator.compare(a.name,b.name)
   }
  })
  val pageSize=45
  val pageCount=((sorted.size+pageSize-1)/pageSize).coerceAtLeast(1)
  browserPage=browserPage.coerceIn(0,pageCount-1)
  val entries=sorted.drop(browserPage*pageSize).take(pageSize)
  val uploadMode=browserUploadSources.isNotEmpty()||browserUploadIsFolder
  val dialog=Dialog(this)
  visibleRemoteChecks.clear();remoteSelectionToolbar=null;remoteSelectionCount=null;remoteConfirmButton=null
  val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8));setBackgroundColor(color(R.color.wb_canvas))}
  val location=LinearLayout(this).apply{orientation=0;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(0,dp(2),0,dp(2))}
  val parentButton=actionButton("↑",color(R.color.wb_surface_raised),color(R.color.wb_secondary)){if(path!="/"){dialog.dismiss();openRemoteBrowser(path.substringBeforeLast('/').ifBlank{"/"})}}.apply{contentDescription=t(if(path=="/")"Already at filesystem root" else "Go to parent folder");isEnabled=path!="/";alpha=if(isEnabled)1f else .45f}
  location.addView(parentButton,LinearLayout.LayoutParams(dp(48),dp(48)))
  location.addView(TextView(this).apply{text=path;textSize=13f;typeface=Typeface.MONOSPACE;setTextColor(color(R.color.wb_secondary));maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.MIDDLE;setPadding(dp(10),0,dp(6),0);contentDescription=t("Current watch folder ${path}")},LinearLayout.LayoutParams(0,-2,1f))
  location.addView(actionButton(if(path=="/")"Storage" else "Root",color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){dialog.dismiss();openRemoteBrowser(if(path=="/")"/sdcard" else "/")}.apply{contentDescription=t(if(path=="/")"Open shared storage at /sdcard" else "Go to filesystem root")},LinearLayout.LayoutParams(-2,dp(48)))
  val more=actionButton("⋮",color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){}.apply{contentDescription=t("More watch storage actions")}
  location.addView(more,LinearLayout.LayoutParams(dp(48),dp(48)).apply{leftMargin=dp(4)})
  body.addView(location)
  val sortNames=arrayOf(t("Name A–Z"),t("Date modified"),t("Size"),t("Type"))
  val sortLabels=arrayOf(t("Name"),t("Date"),t("Size"),t("Type"))
  body.addView(TextView(this).apply{text=t("${sorted.size} items  ·  Sort: ${sortLabels[browserSort]}");textSize=11f;setTextColor(color(R.color.wb_on_surface_variant));contentDescription=t("${sorted.size} items, sorted by ${sortNames[browserSort]}");gravity=android.view.Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(-1,dp(28)))
  more.setOnClickListener{val menu=PopupMenu(this,more);menu.menu.add(0,1,0,t(if(path=="/")"Open shared storage" else "Go to filesystem root"));menu.menu.add(0,2,1,t("Sort by ${sortLabels[browserSort]}"));if(!uploadMode)menu.menu.add(0,3,2,t("Ask before delete · ${if(confirmDelete)"On" else "Off"}"));menu.menu.add(0,4,3,t("Close browser"));menu.setOnMenuItemClickListener{item->when(item.itemId){1->{dialog.dismiss();openRemoteBrowser(if(path=="/")"/sdcard" else "/")};2->{dialog.dismiss();selectBrowserSort(path)};3->{confirmDelete=!confirmDelete;getSharedPreferences("wearbridge_files",MODE_PRIVATE).edit().putBoolean("confirm_delete",confirmDelete).apply();Toast.makeText(this,t("Delete confirmation ${if(confirmDelete)"enabled" else "disabled"}"),Toast.LENGTH_SHORT).show()};4->dialog.dismiss()};true};menu.show()}
  val scroll=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=true}
  val rows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  entries.forEach{entry->
   val modified=if(entry.modifiedEpoch>0)java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(java.util.Date(entry.modifiedEpoch*1000)) else "Date unavailable"
   val detail=if(entry.directory)"Folder · ${modified}" else "${formatBytes(entry.size)} · ${modified}"
   val child=if(path=="/")"/${entry.name}" else "${path}/${entry.name}"
   val row=remoteRow((if(entry.directory)"▰  " else "▤  ")+entry.name,detail,entry.directory){
    if(!uploadMode&&selectedRemotePaths.isNotEmpty()){toggleRemoteSelection(child,entry.directory);return@remoteRow}
    dialog.dismiss()
    if(entry.directory)openRemoteBrowser(child) else if(uploadMode)Toast.makeText(this,t("Choose the destination folder, then tap Upload here"),Toast.LENGTH_SHORT).show() else beginRemoteDownload(child)
   } as LinearLayout
   if(!uploadMode){
    val check=CheckBox(this).apply{isChecked=selectedRemotePaths.containsKey(child);contentDescription=t("Select ${entry.name}");setOnClickListener{setRemoteSelection(child,entry.directory,isChecked)}}
    visibleRemoteChecks[child]=check
    row.addView(check,LinearLayout.LayoutParams(dp(48),dp(48)))
    row.setOnLongClickListener{toggleRemoteSelection(child,entry.directory);true}
   }
   rows.addView(row)
  }
  if(entries.isEmpty())rows.addView(LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER;setPadding(dp(24),dp(24),dp(24),dp(24))
   addView(TextView(this@MainActivity).apply{text=t("Nothing here yet");textSize=17f;typeface=Typeface.DEFAULT_BOLD;gravity=android.view.Gravity.CENTER;setTextColor(color(R.color.wb_on_surface))})
   addView(TextView(this@MainActivity).apply{text=t(if(path.startsWith("/sdcard"))"This folder is empty. Choose another folder or upload files here." else "This folder is empty.");textSize=13f;gravity=android.view.Gravity.CENTER;setTextColor(color(R.color.wb_on_surface_variant));setPadding(0,dp(6),0,0)})
  },LinearLayout.LayoutParams(-1,dp(160)))
  scroll.addView(rows)
  body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  val paging=LinearLayout(this).apply{orientation=0;gravity=android.view.Gravity.CENTER_VERTICAL}
  if(pageCount>1){
   paging.addView(actionButton("Previous",color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){dialog.dismiss();browserPage--;showRemoteListing(path)},LinearLayout.LayoutParams(0,dp(48),1f).apply{rightMargin=dp(6)})
   paging.addView(TextView(this).apply{text=t("Page ${browserPage+1} of ${pageCount}");textSize=12f;gravity=android.view.Gravity.CENTER;setTextColor(color(R.color.wb_on_surface_variant))},LinearLayout.LayoutParams(-2,dp(48)))
   paging.addView(actionButton("More",color(R.color.wb_surface_raised),color(R.color.wb_on_surface)){dialog.dismiss();browserPage++;showRemoteListing(path)},LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(6)})
  }else paging.addView(TextView(this).apply{text=t("Folders first");textSize=12f;setTextColor(color(R.color.wb_on_surface_variant))},LinearLayout.LayoutParams(-1,dp(40)))
  body.addView(paging)
  if(uploadMode){
   body.addView(actionButton("Upload here",color(R.color.wb_primary),color(R.color.wb_on_primary)){dialog.dismiss();confirmUploadTo(path)},LinearLayout.LayoutParams(-1,dp(52)))
  }else{
   val toolbar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(6),dp(4),dp(6),dp(4));background=rounded(color(R.color.wb_surface_raised),dp(16));visibility=if(selectedRemotePaths.isEmpty())View.GONE else View.VISIBLE}
   val selectionRow=LinearLayout(this).apply{orientation=0;gravity=android.view.Gravity.CENTER_VERTICAL}
   remoteSelectionCount=TextView(this).apply{text=t("${selectedRemotePaths.size} selected");textSize=13f;typeface=Typeface.DEFAULT_BOLD;setTextColor(color(R.color.wb_on_surface));gravity=android.view.Gravity.CENTER_VERTICAL;contentDescription=t("Selected items");maxLines=1}
   toolbar.addView(remoteSelectionCount,LinearLayout.LayoutParams(0,dp(48),1f))
   toolbar.addView(actionButton("×",Color.TRANSPARENT,color(R.color.wb_on_surface_variant)){selectedRemotePaths.clear();updateRemoteSelectionUi()}.apply{contentDescription=t("Clear selection")},LinearLayout.LayoutParams(dp(48),dp(48)))
   toolbar.addView(actionButton("↓",color(R.color.wb_primary),color(R.color.wb_on_primary)){dialog.dismiss();downloadSelectedRemotePaths()}.apply{contentDescription=t("Download selected files")},LinearLayout.LayoutParams(dp(48),dp(48)))
   toolbar.addView(actionButton("⌫",color(R.color.wb_error),color(R.color.wb_on_primary)){dialog.dismiss();deleteSelectedRemotePaths()}.apply{contentDescription=t("Delete selected files and folders")},LinearLayout.LayoutParams(dp(48),dp(48)))
   remoteSelectionToolbar=toolbar
   body.addView(toolbar,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
  }
  dialog.setContentView(body)
  dialog.show()
  dialog.window?.apply{setBackgroundDrawable(ColorDrawable(color(R.color.wb_canvas)));setLayout(-1,-1);statusBarColor=color(R.color.wb_canvas);navigationBarColor=color(R.color.wb_canvas);setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)}
 }

 private fun selectBrowserSort(path:String){
  val labels=arrayOf(t("Name · A–Z"),t("Date modified · newest first"),t("Size · largest first"),t("Type · extension"))
  val dialog=AlertDialog.Builder(android.view.ContextThemeWrapper(this,R.style.WearBridgeTheme)).setTitle(t("Sort files")).setSingleChoiceItems(labels,browserSort){dialog,which->browserSort=which;dialog.dismiss();showRemoteListing(path)}.setNegativeButton(t("Cancel")){dialog,_->dialog.dismiss();showRemoteListing(path)}.setOnCancelListener{showRemoteListing(path)}.show()
  dialog.window?.setBackgroundDrawable(ColorDrawable(color(R.color.wb_surface)))
 }

 private fun toggleRemoteSelection(path:String,isDirectory:Boolean){setRemoteSelection(path,isDirectory,!selectedRemotePaths.containsKey(path))}

 private fun setRemoteSelection(path:String,isDirectory:Boolean,selected:Boolean){
  if(selected)selectedRemotePaths[path]=isDirectory else selectedRemotePaths.remove(path)
  updateRemoteSelectionUi()
 }

 private fun updateRemoteSelectionUi(){
  remoteSelectionCount?.text=t("${selectedRemotePaths.size} selected")
  remoteSelectionToolbar?.visibility=if(selectedRemotePaths.isEmpty())View.GONE else View.VISIBLE
  visibleRemoteChecks.forEach{(path,check)->check.isChecked=selectedRemotePaths.containsKey(path)}
 }

 private fun deleteSelectedRemotePaths(){
  if(selectedRemotePaths.isEmpty()){Toast.makeText(this,t("Select files or folders first"),Toast.LENGTH_SHORT).show();return}
  val paths=selectedRemotePaths.keys.toList();val root=pendingFileRoot
  val dispatch={sendRemoteDelete(paths,root)}
  if(confirmDelete)showDeleteConfirmation(paths,dispatch) else dispatch()
 }

 private fun showDeleteConfirmation(paths:List<String>,onConfirm:()->Unit){
  val dontAsk=CheckBox(this).apply{text=t("Do not ask before deleting");setTextColor(color(R.color.wb_on_surface));isChecked=false}
  val content=LinearLayout(this).apply{orientation=1;setPadding(dp(22),dp(8),dp(22),0)}
  content.addView(TextView(this).apply{text=paths.take(5).joinToString("\\n")+(if(paths.size>5)"\\n… and ${paths.size-5} more" else "");textSize=13f;typeface=Typeface.MONOSPACE;setTextColor(color(R.color.wb_on_surface))})
  content.addView(dontAsk)
  AlertDialog.Builder(android.view.ContextThemeWrapper(this,R.style.WearBridgeTheme)).setTitle(t("Delete ${paths.size} item(s)?")).setView(content).setNegativeButton(t("Cancel"),null).setPositiveButton(t("Delete")){_,_->if(dontAsk.isChecked){confirmDelete=false;getSharedPreferences("wearbridge_files",MODE_PRIVATE).edit().putBoolean("confirm_delete",false).apply()};onConfirm()}.show()
 }

 private fun sendRemoteDelete(paths:List<String>,root:Boolean){
  val target=nodeId?:run{append("Watch not connected in Wear Data Layer\\n");return}
  val id=java.util.UUID.randomUUID().toString();val waiter=CompletableDeferred<JSONObject>();deleteWaiters[id]=waiter
  val request=JSONObject().put("id",id).put("paths",org.json.JSONArray(paths)).put("root",root)
  append("Deleting ${paths.size} watch item(s)…\\n")
  scope.launch{
   try{
    Wearable.getMessageClient(this@MainActivity).sendMessage(target,"/file-transfer/delete",request.toString().toByteArray()).await()
    val result=withTimeout(60_000){waiter.await()}
    val requestError=result.optString("error")
    val deleted=result.optJSONArray("deleted")?:org.json.JSONArray();val errors=result.optJSONArray("errors")?:org.json.JSONArray()
    withContext(Dispatchers.Main){
     for(i in 0 until deleted.length())selectedRemotePaths.remove(deleted.optString(i))
     append("Deleted ${deleted.length()} item(s)\\n")
     if(requestError.isNotBlank())append("Delete rejected: $requestError\\n")
     for(i in 0 until errors.length())append("Delete failed: ${errors.optString(i)}\\n")
     if(errors.length()==0)Toast.makeText(this@MainActivity,t("Deleted ${deleted.length()} item(s)"),Toast.LENGTH_SHORT).show()
     requestRemoteListing(browserPath,0)
    }
   }catch(t:Throwable){withContext(Dispatchers.Main){append("Delete request failed: ${t.message}\\n")}}
   finally{deleteWaiters.remove(id)}
  }
 }

 private fun downloadSelectedRemotePaths(){
  if(selectedRemotePaths.isEmpty()){Toast.makeText(this,t("Select files or folders first"),Toast.LENGTH_SHORT).show();return}
  pendingFileRoot=rootMode
  confirmRootFileAction("Download ${selectedRemotePaths.size} selected items"){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),REQUEST_DOWNLOAD_TREE)}
 }

 private fun remoteRow(title:String,subtitle:String,isDirectory:Boolean,onClick:()->Unit):View{
  val row=LinearLayout(this).apply{orientation=0;gravity=android.view.Gravity.CENTER_VERTICAL;minimumHeight=dp(56);setPadding(dp(10),dp(5),dp(8),dp(5));background=rounded(color(R.color.wb_surface),dp(12));setOnClickListener{onClick()};contentDescription=t("$title, $subtitle")}
  val labelBox=LinearLayout(this).apply{orientation=1}
  labelBox.addView(TextView(this).apply{text=title;textSize=14f;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.MIDDLE;setTextColor(color(R.color.wb_on_surface))})
  labelBox.addView(TextView(this).apply{text=subtitle;textSize=11f;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setTextColor(color(R.color.wb_on_surface_variant));setPadding(0,dp(2),0,0)})
  row.addView(TextView(this).apply{text=if(isDirectory)"›" else "";textSize=22f;setTextColor(color(R.color.wb_secondary));gravity=android.view.Gravity.CENTER},LinearLayout.LayoutParams(dp(24),-1))
  row.addView(labelBox,LinearLayout.LayoutParams(0,-2,1f))
  row.layoutParams=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(2),0,dp(2))}
  return row
 }

 private fun formatBytes(bytes:Long):String=when{bytes<1024->"$bytes B";bytes<1024*1024->"${bytes/1024} KB";else->"${bytes/(1024*1024)} MB"}

 private fun confirmUploadTo(remotePath:String){
  if(browserUploadSources.isEmpty()&&!browserUploadIsFolder){Toast.makeText(this,t("No phone files were selected"),Toast.LENGTH_SHORT).show();return}
  pendingRemotePath=remotePath;pendingFileRoot=rootMode
  val transfers=browserUploadSources.map{QueuedFileTransfer("upload",joinWatchPath(remotePath,it.relativePath),it.uri,rootMode)}
  val directories=(listOf(remotePath)+browserUploadFolders.map{joinWatchPath(remotePath,it)}).distinct().sortedBy{it.count{c->c=='/'}}
  confirmRootFileAction("Upload ${transfers.size} file(s) to $remotePath"){queueFileTransfers(transfers,directories)}
 }

 private fun joinWatchPath(base:String,relative:String):String=(base.trimEnd('/')+"/"+relative.trimStart('/')).ifBlank{"/$relative"}

 private fun beginRemoteDownload(path:String){
  pendingRemotePath=path;pendingFileRoot=rootMode
  confirmRootFileAction("Download $path"){
   val name=path.substringAfterLast('/').ifBlank{"watch-file"}
   val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,name)
   startActivityForResult(intent,REQUEST_DOWNLOAD)
  }
 }

 private fun confirmRootFileAction(label:String,action:()->Unit){
  if(!pendingFileRoot){action();return}
  AlertDialog.Builder(android.view.ContextThemeWrapper(this,R.style.WearBridgeTheme)).setTitle(t("ROOT file access")).setMessage(t(label)+"\n\n"+t("This operation will run as root on the watch."))
   .setNegativeButton(t("Cancel"),null).setPositiveButton(t("Continue")){_,_->action()}.show()
 }

 private fun documentName(uri:Uri):String{
  val column=android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
  return try{contentResolver.query(uri,arrayOf(column),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}.orEmpty()}catch(_:Throwable){""}
 }

 private fun safePathComponent(value:String):String{
  val clean=value.map{if(it=='/'||it=='\\'||it=='\u0000'||it=='\n'||it=='\r')'_' else it}.joinToString("").trim()
  return clean.takeIf{it.isNotBlank()&&it!="."&&it!=".."}?:"unnamed"
 }

 private fun enumeratePhoneTree(treeUri:Uri):Pair<List<UploadSource>,List<String>>{
  val rootId=android.provider.DocumentsContract.getTreeDocumentId(treeUri)
  val rootName=safePathComponent(documentName(android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri,rootId)).ifBlank{"folder"})
  val files=ArrayList<UploadSource>();val directories=ArrayList<String>();directories.add(rootName)
  fun walk(parentId:String,relative:String,depth:Int){
   if(depth>32)throw IOException("Phone folder nesting exceeds 32 levels")
   val children=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,parentId)
   contentResolver.query(children,arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use{cursor->
    val idCol=cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID);val nameCol=cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME);val mimeCol=cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
    while(cursor.moveToNext()){
     val id=cursor.getString(idCol);val name=safePathComponent(cursor.getString(nameCol).orEmpty())
     val rel="$relative/$name";val mime=cursor.getString(mimeCol)
     if(mime==android.provider.DocumentsContract.Document.MIME_TYPE_DIR){directories.add(rel);if(directories.size>5000)throw IOException("Phone folder contains too many directories");walk(id,rel,depth+1)}
     else{files.add(UploadSource(rel,android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri,id)));if(files.size>5000)throw IOException("Phone folder contains more than 5000 files")}
    }
   }
  }
  walk(rootId,rootName,0)
  return files to directories
 }

 private fun buildDownloadTargets(treeUri:Uri,plan:DownloadPlan,root:Boolean):List<QueuedFileTransfer>{
  val rootId=android.provider.DocumentsContract.getTreeDocumentId(treeUri);val rootUri=android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri,rootId)
  val directoryUris=hashMapOf("" to rootUri)
  fun ensureDirectory(relative:String):Uri{
   directoryUris[relative]?.let{return it}
   val parent=relative.substringBeforeLast('/',"");val name=relative.substringAfterLast('/')
   val parentUri=ensureDirectory(parent)
   val created=android.provider.DocumentsContract.createDocument(contentResolver,parentUri,android.provider.DocumentsContract.Document.MIME_TYPE_DIR,name)?:throw IOException("Could not create phone folder $relative")
   directoryUris[relative]=created;return created
  }
  plan.directories.distinct().sortedBy{it.count{c->c=='/'}}.forEach{if(it.isNotBlank())ensureDirectory(it)}
  return plan.files.map{file->
   val parent=file.relativePath.substringBeforeLast('/',"");val name=file.relativePath.substringAfterLast('/')
   val parentUri=ensureDirectory(parent)
   val created=android.provider.DocumentsContract.createDocument(contentResolver,parentUri,"application/octet-stream",name)?:throw IOException("Could not create phone file ${file.relativePath}")
   QueuedFileTransfer("download",file.remotePath,created,root)
  }
 }

 private suspend fun buildRemoteDownloadBatch(selected:Map<String,Boolean>,treeUri:Uri,root:Boolean):List<QueuedFileTransfer> = withContext(Dispatchers.IO){
  val files=ArrayList<DownloadSource>();val dirs=linkedSetOf<String>();val visited=linkedSetOf<String>()
  val parentParts=selected.keys.map{path->path.substringBeforeLast('/',"").split('/').filter{it.isNotEmpty()}}
  val common=ArrayList<String>();if(parentParts.isNotEmpty())for(index in 0 until (parentParts.minOfOrNull{it.size}?:0)){val part=parentParts.first()[index];if(parentParts.all{it[index]==part})common.add(part)else break}
  val base="/"+common.joinToString("/")
  selected.forEach{(path,isDirectory)->
   val relative=path.removePrefix(base).trimStart('/').ifBlank{path.substringAfterLast('/').ifBlank{"root"}}
   collectRemoteFiles(path,relative,isDirectory,root,files,dirs,visited)
  }
  val unique=files.distinctBy{it.remotePath}
  if(unique.size>5000)throw IOException("Selection exceeds 5000 files")
  buildDownloadTargets(treeUri,DownloadPlan(unique,dirs.toList()),root)
 }

 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
  super.onActivityResult(requestCode,resultCode,data)
  if(resultCode!=RESULT_OK)return
  val uri=data?.data
  when(requestCode){
   REQUEST_UPLOAD->{
    val sources=ArrayList<UploadSource>()
    val usedNames=hashSetOf<String>()
    fun addUploadUri(item:Uri){
     val raw=try{contentResolver.query(item,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}}catch(_:Throwable){null}
     val base=safePathComponent((raw?:"upload.bin").substringAfterLast('/'));var candidate=base;var suffix=2
     while(!usedNames.add(candidate)){val dot=base.lastIndexOf('.').takeIf{it>0}?:base.length;candidate=base.substring(0,dot)+" ($suffix)"+base.substring(dot);suffix++}
     sources.add(UploadSource(candidate,item))
    }
    val clip=data?.clipData
    if(clip!=null)for(i in 0 until clip.itemCount){val item=clip.getItemAt(i).uri?:continue;addUploadUri(item)}
    else if(uri!=null)addUploadUri(uri)
    if(sources.isEmpty()){Toast.makeText(this,t("No files were selected"),Toast.LENGTH_SHORT).show();return}
    browserUploadSources=sources;browserUploadIsFolder=false;browserUploadFolders=emptyList();rootBrowserConfirmed=false;openRemoteBrowser("/sdcard")
   }
   REQUEST_UPLOAD_TREE->{
    val tree=uri?:return
    val flags=(data?.flags?:0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    try{contentResolver.takePersistableUriPermission(tree,flags)}catch(_:Throwable){}
    append("Reading phone folder…\n")
    scope.launch{try{val pair=withContext(Dispatchers.IO){enumeratePhoneTree(tree)};withContext(Dispatchers.Main){browserUploadSources=pair.first;browserUploadFolders=pair.second;browserUploadIsFolder=true;rootBrowserConfirmed=false;openRemoteBrowser("/sdcard")}}catch(t:Throwable){append("Could not read phone folder: ${t.message}\n")}}
   }
   REQUEST_DOWNLOAD->{if(uri!=null)beginWifiFileTransfer("download",pendingRemotePath,uri,pendingFileRoot)}
   REQUEST_DOWNLOAD_TREE->{
    val tree=uri?:return
    val flags=(data?.flags?:0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    try{contentResolver.takePersistableUriPermission(tree,flags)}catch(_:Throwable){}
    val selection=selectedRemotePaths.toMap();val root=pendingFileRoot
    append("Preparing ${selection.size} selected watch item(s)…\n")
    scope.launch{try{val transfers=buildRemoteDownloadBatch(selection,tree,root);withContext(Dispatchers.Main){selectedRemotePaths.clear();queueFileTransfers(transfers,emptyList())}}catch(t:Throwable){append("Could not prepare watch download: ${t.message}\n")}}
   }
  }
 }

 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==REQUEST_NEARBY_WIFI){
   val action=pendingNearbyPermissionAction;pendingNearbyPermissionAction=null
   if(grantResults.isNotEmpty()&&grantResults[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)action?.invoke()
   else{append("Nearby devices permission denied; local Wi-Fi transfer needs it on this Android build.\n");Toast.makeText(this,t("Allow Nearby devices to transfer files over Wi-Fi"),Toast.LENGTH_LONG).show()}
  }
 }

 private fun beginWifiFileTransfer(operation:String,path:String,uri:Uri,root:Boolean){
  queueFileTransfers(listOf(QueuedFileTransfer(operation,path,uri,root)),emptyList())
 }

 private fun queueFileTransfers(transfers:List<QueuedFileTransfer>,directories:List<String>){
  if(fileBatchActive||pendingFileTransfer!=null){append("A Wi-Fi file transfer is already active\n");return}
  val target=nodeId?:run{append("Watch not connected in Wear Data Layer\n");return}
  val permission="android.permission.NEARBY_WIFI_DEVICES"
  if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(permission)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
   if(pendingNearbyPermissionAction!=null)return
   pendingNearbyPermissionAction={queueFileTransfers(transfers,directories)}
   requestPermissions(arrayOf(permission),REQUEST_NEARBY_WIFI);return
  }
  fileBatchActive=true;transferBatchTotal=transfers.size;transferBatchDone=0;transferBatchFailed=0
  fileTransferQueue.clear();fileTransferQueue.addAll(transfers)
  append("Wi-Fi file batch starting · ${transfers.size} file(s) · ${if(transfers.firstOrNull()?.root==true)"ROOT" else "SHELL"}\n")
  scope.launch{
   try{directories.distinct().forEach{ensureWatchDirectory(it,transfers.firstOrNull()?.root?:rootMode)};dispatchNextFileTransfer()}
   catch(t:Throwable){fileBatchActive=false;fileTransferQueue.clear();append("Could not prepare watch folders: ${t.message}\n")}
  }
 }

 private fun dispatchNextFileTransfer(){
  if(pendingFileTransfer!=null)return
  val next=fileTransferQueue.pollFirst()
  if(next==null){if(fileBatchActive){fileBatchActive=false;append("Wi-Fi file batch finished · ${transferBatchDone-transferBatchFailed}/${transferBatchTotal} succeeded · $transferBatchFailed failed\n")};return}
  val target=nodeId?:run{append("Watch not connected in Wear Data Layer\n");return}
  val id=java.util.UUID.randomUUID().toString();val key=ByteArray(32).also{SecureRandom().nextBytes(it)}
  val transfer=PendingFileTransfer(id,key,next.operation,next.path,next.uri,next.root);pendingFileTransfer=transfer
  val payload=JSONObject().put("id",id).put("key",android.util.Base64.encodeToString(key,android.util.Base64.NO_WRAP)).put("path",next.path).put("root",next.root)
  append("[${transferBatchDone+1}/$transferBatchTotal] ${next.operation} · ${next.path}\n")
  scope.launch{try{Wearable.getMessageClient(this@MainActivity).sendMessage(target,"/file-transfer/${next.operation}",payload.toString().toByteArray(Charsets.UTF_8)).await()}catch(t:Throwable){if(pendingFileTransfer?.id==id)pendingFileTransfer=null;transferBatchDone++;transferBatchFailed++;append("Could not request transfer: ${t.message}\n");dispatchNextFileTransfer()}}
 }

 private fun handleWifiFileReady(json:String){
  val response=try{JSONObject(json)}catch(t:Throwable){append("Invalid Wi-Fi transfer response\n");return}
  val transfer=pendingFileTransfer?:return
  if(response.optString("id")!=transfer.id)return
  if(!response.optBoolean("ok",false)){pendingFileTransfer=null;transferBatchDone++;transferBatchFailed++;append("Wi-Fi transfer unavailable: ${response.optString("error","unknown error")}\n");dispatchNextFileTransfer();return}
  val host=response.optString("host");val port=response.optInt("port",0)
  if(host.isBlank()||port !in 1..65535){pendingFileTransfer=null;transferBatchDone++;transferBatchFailed++;append("Watch returned an invalid Wi-Fi endpoint\n");dispatchNextFileTransfer();return}
  append("Watch Wi-Fi endpoint ready; opening encrypted transfer\n")
  scope.launch(Dispatchers.IO){
   var succeeded=false
   try{performWifiFileTransfer(transfer,host,port);succeeded=true;withContext(Dispatchers.Main){append("Completed · ${transfer.path}\n")}}
   catch(t:Throwable){val detail=generateSequence(t as Throwable?){it.cause}.mapNotNull{it.message?.takeIf(String::isNotBlank)}.firstOrNull()?:t.javaClass.simpleName;withContext(Dispatchers.Main){append("Transfer failed · ${transfer.path}: $detail\n")}}
   finally{withContext(Dispatchers.Main){if(pendingFileTransfer?.id==transfer.id)pendingFileTransfer=null;transferBatchDone++;if(!succeeded)transferBatchFailed++;dispatchNextFileTransfer()}}
  }
 }

 private fun performWifiFileTransfer(transfer:PendingFileTransfer,host:String,port:Int){
  val address=InetAddress.getByName(host)
  val connectivity=getSystemService(ConnectivityManager::class.java)?:throw IOException("Connectivity service unavailable")
  val wifi=connectivity.allNetworks.firstOrNull{network->
   val caps=connectivity.getNetworkCapabilities(network)
   caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true &&
    (connectivity.getLinkProperties(network)?.routes?.any{it.matches(address)}==true)
  }?:throw IOException("No Wi-Fi route to watch $host; connect both devices to the same LAN")
  val endpoint=InetSocketAddress(address,port)
  val socket=try{
   wifi.socketFactory.createSocket().also{bound->
    try{bound.connect(endpoint,15_000)}catch(t:Throwable){try{bound.close()}catch(_:Exception){};throw t}
   }
  }catch(boundError:Throwable){
   val detail=boundError.message.orEmpty()
   if(!detail.contains("EPERM",ignoreCase=true)&&!detail.contains("Operation not permitted",ignoreCase=true))throw boundError
   val fallback=Socket()
   try{fallback.connect(endpoint,15_000);fallback}
   catch(fallbackError:Throwable){try{fallback.close()}catch(_:Exception){};fallbackError.addSuppressed(boundError);throw IOException("Wi-Fi socket binding was denied and the default route could not reach the watch. Check VPN/Always-on VPN and local-network permission: ${fallbackError.message}",fallbackError)}
  }
  socket.use{
   it.soTimeout=300_000
   val input=DataInputStream(BufferedInputStream(it.getInputStream()));val output=DataOutputStream(BufferedOutputStream(it.getOutputStream()))
   WifiFileProtocol.authenticateClient(input,output,transfer.key,transfer.id)
   if(transfer.operation=="upload"){
    val sourceUri=transfer.uri?:throw IOException("Phone file was not selected")
    val source=contentResolver.openInputStream(sourceUri)?:throw IOException("Could not open selected phone file")
    source.use{stream->val writer=WifiFileProtocol.writer(output,transfer.key,transfer.id);val buffer=ByteArray(WifiFileProtocol.CHUNK_SIZE);while(true){val n=stream.read(buffer);if(n<0)break;if(n>0)writer.write(WifiFileProtocol.DATA,buffer.copyOf(n))};writer.write(WifiFileProtocol.END)}
   }
   val reader=WifiFileProtocol.reader(input,transfer.key,transfer.id)
   var outputFile:java.io.OutputStream?=null
   if(transfer.operation=="download")outputFile=contentResolver.openOutputStream(transfer.uri?:throw IOException("Phone destination was not selected"),"wt")?:throw IOException("Could not open selected phone destination")
   try{
    transferRecords@ while(true){val record=reader.read();when(record.type){
     WifiFileProtocol.DATA->{if(transfer.operation!="download")throw IOException("Unexpected file data response");outputFile!!.write(record.payload)}
     WifiFileProtocol.RESULT->{if(record.payload.size<4)throw IOException("Invalid transfer result");val code=java.nio.ByteBuffer.wrap(record.payload).int;val message=record.payload.copyOfRange(4,record.payload.size).toString(Charsets.UTF_8);if(code!=0)throw IOException("watch exitCode=$code${if(message.isBlank())"" else ": $message"}");break@transferRecords}
     else->throw IOException("Unexpected Wi-Fi transfer record")
    }}
   }finally{try{outputFile?.flush();outputFile?.close()}catch(_:Exception){}}
  }
 }

 companion object { private const val REQUEST_UPLOAD=7301; private const val REQUEST_DOWNLOAD=7302; private const val REQUEST_NEARBY_WIFI=7303; private const val REQUEST_UPLOAD_TREE=7304; private const val REQUEST_DOWNLOAD_TREE=7305; private const val MAX_OUTPUT_CHARS=262144; private const val OUTPUT_TRIM_BATCH=32768; private const val EMPTY_OUTPUT="›  Output will appear here\n   Run in NORMAL or connect STREAM." }
 private fun markStreamLost(reason:String){streamReady=false;stream=false;refreshModeButtons();channel=null;try{channelInput?.close()}catch(_:Exception){};try{channelOutput?.close()}catch(_:Exception){};channelInput=null;channelOutput=null;stopLocalTerminal();mode.text=t("STREAM · disconnected");append("Stream disconnected: $reason\n")}
 private fun append(text:String){
  val wasAtBottom=if(::outputScroll.isInitialized&&::output.isInitialized)outputScroll.scrollY+outputScroll.height>=output.height-dp(40) else true
  if(!hasOutput){output.text=t("");hasOutput=true}
  output.append(text)
  fullscreenPlainOutput?.text=output.text
  if(output.text.length>MAX_OUTPUT_CHARS){
   val trimPoint=(output.text.length-(MAX_OUTPUT_CHARS-OUTPUT_TRIM_BATCH)).coerceAtLeast(0)
   val newline=output.text.indexOf('\n',trimPoint)
   val start=if(newline>=0)newline+1 else trimPoint
   output.text=t("… earlier output trimmed …\n")+output.text.subSequence(start,output.text.length)
   output.contentDescription=t("Terminal output; earlier output trimmed to limit memory use")
  }else output.contentDescription=t("Terminal output")
  if(wasAtBottom&&::outputScroll.isInitialized)outputScroll.post{outputScroll.fullScroll(View.FOCUS_DOWN)}
 }
 override fun onDestroy(){Wearable.getMessageClient(this).removeListener(this);if(stream)closeStream() else stopLocalTerminal();scope.cancel();super.onDestroy()}
}
