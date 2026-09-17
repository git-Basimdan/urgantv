package com.samuelspring.urgantv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.gms.security.ProviderInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLHandshakeException

data class Channel(val name: String, val url: String, val group: String = "Diğer")

class MainActivity : AppCompatActivity() {

    private val PREF_NAME = "IptvPrefs"
    private val KEY_M3U_URL = "m3u_url"
    private var player: ExoPlayer? = null
    private lateinit var recyclerViewChannels: RecyclerView
    private var channelList = mutableListOf<Channel>()
    private var currentPlayingIndex = -1
    private var channelAdapter: ChannelAdapter? = null

    private val leftKeyHandler = Handler(Looper.getMainLooper())
    private var leftKeyRunnable: Runnable? = null
    private var isLeftKeyPressed = false
    private var leftLongPressTriggered = false

    // Kanal listesi 15 sn hareketsizlikten sonra otomatik kapanır
    private val listAutoHideHandler = Handler(Looper.getMainLooper())
    private var listAutoHideRunnable: Runnable? = null

    private fun scheduleListAutoHide() {
        listAutoHideRunnable?.let { listAutoHideHandler.removeCallbacks(it) }
        listAutoHideRunnable = Runnable {
            recyclerViewChannels.visibility = View.GONE
        }
        listAutoHideHandler.postDelayed(listAutoHideRunnable!!, 15000)
    }

    private fun cancelListAutoHide() {
        listAutoHideRunnable?.let { listAutoHideHandler.removeCallbacks(it) }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                saveAndLoadNewList(it.toString())
            } catch (e: Exception) {
                showCustomToast("Dosya izni alınamadı")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerViewChannels = findViewById(R.id.recyclerViewChannels)

        // Bazı IPTV sunucuları isteğin User-Agent'ına bakıp tarayıcı/VLC gibi görünmeyen
        // istekleri (ExoPlayer'ın varsayılanı gibi) reddedebiliyor. Tarayıcıda çalışıp
        // uygulamada çalışmayan kanalların çoğu bu yüzdendir - bu yüzden tarayıcı gibi
        // görünen bir User-Agent ile isteği gönderiyoruz, ayrıca cross-protocol redirect'e
        // (http<->https yönlendirmesi) de izin veriyoruz.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        super.onPlayerError(error)
                        // Sinyal kesintisi / hata durumunda otomatik olarak başka kanala GEÇİLMİYOR.
                        // Kullanıcı yukarı/aşağı ile manuel geçiş yapmalı.
                        Log.e("UrganTV_Player", "Playback error [${error.errorCodeName}]: ${error.message}", error)

                        // IO_BAD_HTTP_STATUS geniş bir şemsiye (401/403/404/500 hepsi bu kodun altına
                        // düşer) - asıl sebebi anlamak için sebep zincirinde gerçek HTTP durum kodunu
                        // taşıyan InvalidResponseCodeException'ı arıyoruz.
                        var cause: Throwable? = error.cause
                        var httpEx: HttpDataSource.InvalidResponseCodeException? = null
                        while (cause != null) {
                            if (cause is HttpDataSource.InvalidResponseCodeException) {
                                httpEx = cause
                                break
                            }
                            cause = cause.cause
                        }

                        if (httpEx != null) {
                            Log.e(
                                "UrganTV_Player",
                                "HTTP ${httpEx.responseCode} - ${httpEx.responseMessage}\n" +
                                        "URL: ${httpEx.dataSpec.uri}\n" +
                                        "Headers: ${httpEx.headerFields}"
                            )
                            showCustomToast("Kanal açılamadı: HTTP ${httpEx.responseCode}")
                        } else {
                            showCustomToast("Kanal açılamadı: ${error.errorCodeName}")
                        }
                    }
                })
            }

        findViewById<PlayerView>(R.id.playerView).player = player

        checkM3uUrl()
    }

    private fun showCustomToast(message: String) {
        val layout = layoutInflater.inflate(R.layout.custom_toast, null)
        val text: TextView = layout.findViewById(R.id.textToast)
        text.text = message

        val toast = Toast(applicationContext)
        toast.duration = Toast.LENGTH_SHORT
        toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 150)
        toast.view = layout
        toast.show()
    }

    private fun checkM3uUrl() {
        val sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedUrl = sharedPreferences.getString(KEY_M3U_URL, null)

        if (savedUrl.isNullOrEmpty()) {
            showM3uInputDialog()
        } else {
            loadM3uData(savedUrl)
        }
    }

    private fun showM3uInputDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val input = EditText(context).apply {
            hint = "M3U oynatma listesi URL'sini girin"
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val btnBrowse = Button(context).apply {
            text = "📁 Cihazdan M3U Dosyası Seç (Gözat)"
            setOnClickListener {
                filePickerLauncher.launch(arrayOf("*/*"))
            }
        }

        layout.addView(input)
        layout.addView(btnBrowse)

        AlertDialog.Builder(context)
            .setTitle("IPTV Listesi Ekle")
            .setView(layout)
            .setCancelable(true)
            .setPositiveButton("Kaydet") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    saveAndLoadNewList(url)
                } else {
                    showCustomToast("Link boş olamaz!")
                }
            }
            .setNegativeButton("İptal") { dialog, _ ->
                dialog.dismiss()
                val sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                if (sharedPreferences.getString(KEY_M3U_URL, null).isNullOrEmpty()) {
                    showCustomToast("Devam etmek için bir liste gereklidir.")
                }
            }
            .show()
    }

    private fun saveAndLoadNewList(source: String) {
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_M3U_URL, source).apply()
        player?.stop()
        loadM3uData(source)
    }

    private fun loadM3uData(url: String) {
        showCustomToast("Liste yükleniyor...")

        // GlobalScope yerine lifecycleScope: Activity yok olursa işlem otomatik iptal edilir.
        lifecycleScope.launch {
            try {
                val loadedChannels = parseM3u(this@MainActivity, url)

                if (loadedChannels.isNotEmpty()) {
                    channelList = loadedChannels.toMutableList()
                    showCustomToast("${channelList.size} kanal yüklendi!")

                    val layoutManager = CenterLayoutManager(this@MainActivity)
                    recyclerViewChannels.layoutManager = layoutManager

                    channelAdapter = ChannelAdapter(
                        channelList,
                        onClick = { selectedChannel, position ->
                            currentPlayingIndex = position
                            playChannel(selectedChannel.url)
                            // Liste kapanmıyor; sadece 15 sn'lik hareketsizlik sayacı sıfırlanıyor.
                            scheduleListAutoHide()
                        },
                        onDeleteClick = { position ->
                            showDeleteChannelDialog(position)
                        }
                    )
                    recyclerViewChannels.adapter = channelAdapter
                    // Liste başlangıçta görünür durumda (XML'de visibility="visible"), o yüzden
                    // ilk yüklemede de 15 sn'lik otomatik kapanma sayacı başlatılıyor.
                    scheduleListAutoHide()

                    recyclerViewChannels.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            // Artık grup başlığı ve kanal satırı farklı yükseklikte olabildiği için,
                            // ortalama padding hesabında ilk çocuk yerine görünen en uzun çocuğu kullanıyoruz.
                            val tallestChildHeight = (0 until recyclerViewChannels.childCount)
                                .mapNotNull { recyclerViewChannels.getChildAt(it)?.height }
                                .filter { it > 0 }
                                .maxOrNull()

                            if (tallestChildHeight != null && recyclerViewChannels.height > 0) {
                                val verticalPadding = ((recyclerViewChannels.height - tallestChildHeight) / 2).coerceAtLeast(0)
                                recyclerViewChannels.setPadding(
                                    recyclerViewChannels.paddingLeft,
                                    verticalPadding,
                                    recyclerViewChannels.paddingRight,
                                    verticalPadding
                                )
                                recyclerViewChannels.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            }
                        }
                    })

                } else {
                    showCustomToast("Kanallar okunamadı! Liste boş veya format hatalı.")
                }
            } catch (e: Exception) {
                // Gerçek hata sebebini gösteriyoruz (timeout, 403, 404, SSL vs.)
                // böylece GitHub/CDN kaynaklı sorunlarda tam olarak ne olduğunu görebilirsin.
                Log.e("UrganTV_M3U", "M3U yüklenemedi: $url", e)

                val isCertError = e is SSLHandshakeException ||
                        e.cause is java.security.cert.CertPathValidatorException

                val message = if (isCertError) {
                    "SSL sertifika hatası: cihazın/emülatörün güvenlik sertifikaları güncel değil. " +
                            "Play Store'un güncel olduğu bir cihaz ya da daha yeni bir emülatör imajı dene."
                } else {
                    "Liste yüklenemedi: ${e.message}"
                }
                showCustomToast(message)
            }
        }
    }

    private fun showDeleteChannelDialog(position: Int) {
        if (position < 0 || position >= channelList.size) return
        val channelNameToDelete = channelList[position].name

        AlertDialog.Builder(this)
            .setTitle("Kanalı Sil")
            .setMessage("Emin misiniz?\n\n'$channelNameToDelete' kanalını listeden kaldırmak istiyor musunuz?")
            .setPositiveButton("Evet, Sil") { _, _ ->
                channelList.removeAt(position)
                // Gruplama nedeniyle liste artık başlıklar içerdiğinden (adapter pozisyonu ile
                // channelList indexi aynı değil), tekil notifyItemRemoved yerine tam yeniden kuruyoruz.
                channelAdapter?.refresh()
                showCustomToast("Kanal silindi")
            }
            .setNegativeButton("İptal") { dialog, _ ->
                channelAdapter?.resetDeleteState()
                dialog.dismiss()
            }
            .setOnCancelListener {
                channelAdapter?.resetDeleteState()
            }
            .show()
    }

    private fun playChannel(streamUrl: String) {
        player?.let {
            try {
                val mediaItem = MediaItem.fromUri(streamUrl)
                it.setMediaItem(mediaItem)
                it.prepare()
                it.play()
            } catch (e: Exception) {
                showCustomToast("Yayın yüklenirken hata oluştu")
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_M) {
            if (!isLeftKeyPressed) {
                isLeftKeyPressed = true
                leftLongPressTriggered = false
                leftKeyRunnable = Runnable {
                    leftLongPressTriggered = true
                    showM3uInputDialog()
                    showCustomToast("M3U Menüsü Açıldı")
                }
                leftKeyHandler.postDelayed(leftKeyRunnable!!, 2000)
            }
            return true
        }

        if (recyclerViewChannels.visibility == View.GONE && channelList.isNotEmpty()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (currentPlayingIndex < channelList.size - 1) {
                        currentPlayingIndex++
                        playChannel(channelList[currentPlayingIndex].url)
                        showCustomToast(channelList[currentPlayingIndex].name)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (currentPlayingIndex > 0) {
                        currentPlayingIndex--
                        playChannel(channelList[currentPlayingIndex].url)
                        showCustomToast(channelList[currentPlayingIndex].name)
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_M) {
            isLeftKeyPressed = false
            leftKeyRunnable?.let { leftKeyHandler.removeCallbacks(it) }

            // Kısa basış (uzun basış M3U menüsünü zaten açmadıysa): liste açıksa,
            // o an odaklanılan/bulunulan grubu aç/kapat.
            if (!leftLongPressTriggered) {
                toggleCurrentGroup()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Liste açıkken, o an odaklanılan satırın (kanal ya da başlığın kendisi) ait olduğu
     * grubu açar/kapatır ve odağı grup başlığında tutar. Liste kapalıysa hiçbir şey yapmaz.
     */
    private fun toggleCurrentGroup() {
        if (recyclerViewChannels.visibility != View.VISIBLE) return

        val focusedChild = recyclerViewChannels.focusedChild
        val focusedPosition = if (focusedChild != null) {
            recyclerViewChannels.getChildAdapterPosition(focusedChild)
        } else {
            (recyclerViewChannels.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()
                ?: RecyclerView.NO_POSITION
        }

        if (focusedPosition == RecyclerView.NO_POSITION || focusedPosition < 0) return

        val headerPos = channelAdapter?.toggleGroupAtPosition(focusedPosition) ?: return
        if (headerPos != -1) {
            recyclerViewChannels.post {
                recyclerViewChannels.layoutManager?.findViewByPosition(headerPos)?.requestFocus()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Liste açıkken herhangi bir tuşa basılırsa otomatik kapanma sayacını sıfırla
        if (recyclerViewChannels.visibility == View.VISIBLE) {
            scheduleListAutoHide()
        }
        return super.dispatchKeyEvent(event)
    }

    // Çift geri tuşunu, "liste açık mı kapalı mı" durumuna göre değil,
    // iki basış arasındaki SÜREYE göre ayırt ediyoruz. Böylece:
    // - Yavaş / tek bir geri basışı: sadece listeyi açar/kapatır (toggle).
    // - Hızlı art arda (800ms içinde) iki basış: çıkış onay diyaloğunu gösterir.
    private var lastBackPressTime = 0L
    private val doubleBackPressWindowMs = 800L

    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        val isRapidDoublePress = (now - lastBackPressTime) < doubleBackPressWindowMs
        lastBackPressTime = now

        if (isRapidDoublePress) {
            showExitConfirmationDialog()
            return
        }

        if (recyclerViewChannels.visibility == View.VISIBLE) {
            recyclerViewChannels.visibility = View.GONE
            cancelListAutoHide()
        } else {
            recyclerViewChannels.visibility = View.VISIBLE
            scheduleListAutoHide()

            // Liste açıldığında çalan kanal ortada veya uygun sınırda görünsün.
            // Kanal, kapalı bir grubun içindeyse önce o grup otomatik açılır.
            if (currentPlayingIndex != -1) {
                val adapterPos = channelAdapter?.ensureChannelVisibleAndGetPosition(currentPlayingIndex) ?: -1
                if (adapterPos != -1) {
                    recyclerViewChannels.scrollToPosition(adapterPos)
                    recyclerViewChannels.post {
                        recyclerViewChannels.layoutManager?.findViewByPosition(adapterPos)?.requestFocus()
                    }
                }
            } else if (channelList.isNotEmpty()) {
                recyclerViewChannels.scrollToPosition(0)
                recyclerViewChannels.post {
                    recyclerViewChannels.layoutManager?.findViewByPosition(0)?.requestFocus()
                }
            }
        }
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Uygulamadan Çıkış")
            .setMessage("Uygulamayı kapatmak istiyor musunuz?")
            .setPositiveButton("Evet") { _, _ ->
                finish()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelListAutoHide()
        leftKeyRunnable?.let { leftKeyHandler.removeCallbacks(it) }
        player?.release()
        player = null
    }
}

// Uçlarda normal kayan, ortada ise sabitleyen akıllı LayoutManager
class CenterLayoutManager(context: Context) : LinearLayoutManager(context) {

    // D-pad ile fokus değiştiğinde RecyclerView'in kendi "sadece görünür yap"
    // davranışını iptal edip, seçili öğeyi ortaya kaydırıyoruz.
    override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?
    ): Boolean {
        val position = getPosition(child)
        parent.post {
            smoothScrollToPosition(parent, state, position)
        }
        return true
    }

    override fun scrollToPosition(position: Int) {
        super.scrollToPositionWithOffset(position, height / 2)
    }

    override fun smoothScrollToPosition(recyclerView: RecyclerView, state: RecyclerView.State?, position: Int) {
        val smoothScroller = object : LinearSmoothScroller(recyclerView.context) {
            override fun calculateDtToFit(
                viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int
            ): Int {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }
        }
        smoothScroller.targetPosition = position
        startSmoothScroll(smoothScroller)
    }
}

// Gruplu liste, RecyclerView'da tek bir "flat" (düz) satır listesi olarak gösterilir:
// Header satırları (grup adı) ve Row satırları (kanal). Row, gerçek channelList içindeki
// index'ini de taşır ki tıklama/silme MainActivity'nin beklediği doğru index'i alsın.
sealed class ChannelListItem {
    data class Header(val groupName: String) : ChannelListItem()
    data class Row(val channel: Channel, val channelIndex: Int) : ChannelListItem()
}

class ChannelAdapter(
    private val channels: List<Channel>,
    private val onClick: (Channel, Int) -> Unit,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CHANNEL = 1
    }

    // Hangi grupların açık (expanded) olduğunu tutar. Başlangıçta hepsi açık,
    // böylece gruplama eklenmeden önceki görünümle aynı başlar.
    private val expandedGroups = mutableSetOf<String>().apply {
        addAll(channels.map { it.group }.distinct())
    }

    private var displayItems: List<ChannelListItem> = emptyList()
    private var deleteTargetPosition = -1
    private val handler = Handler(Looper.getMainLooper())
    private var rightKeyRunnable: Runnable? = null
    private var recyclerViewRef: RecyclerView? = null

    init {
        rebuildDisplayList()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerViewRef = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recyclerViewRef = null
    }

    private fun rebuildDisplayList() {
        val items = mutableListOf<ChannelListItem>()
        val groupToIndices = LinkedHashMap<String, MutableList<Int>>()
        channels.forEachIndexed { index, channel ->
            groupToIndices.getOrPut(channel.group) { mutableListOf() }.add(index)
        }
        for ((groupName, indices) in groupToIndices) {
            items.add(ChannelListItem.Header(groupName))
            if (expandedGroups.contains(groupName)) {
                indices.forEach { idx -> items.add(ChannelListItem.Row(channels[idx], idx)) }
            }
        }
        displayItems = items
    }

    /** channelList değiştiğinde (örn. silme) MainActivity'nin çağırması gereken metod. */
    fun refresh() {
        deleteTargetPosition = -1
        rebuildDisplayList()
        notifyDataSetChanged()
    }

    /**
     * Verilen gerçek kanal index'inin grubu kapalıysa açar, sonra bu kanalın
     * o anki flat (adapter) pozisyonunu döner. Kanal bulunamazsa -1 döner.
     */
    fun ensureChannelVisibleAndGetPosition(channelIndex: Int): Int {
        if (channelIndex < 0 || channelIndex >= channels.size) return -1
        val group = channels[channelIndex].group
        if (expandedGroups.add(group)) {
            rebuildDisplayList()
            notifyDataSetChanged()
        }
        return displayItems.indexOfFirst { it is ChannelListItem.Row && it.channelIndex == channelIndex }
    }

    /**
     * Verilen adapter pozisyonunun ait olduğu grubu açar/kapatır. Pozisyondaki satır
     * bir kanal (Row) ise, geriye doğru en yakın grup başlığı (Header) bulunup o kullanılır.
     * Yeni odaklanılması gereken başlığın pozisyonunu döner, bulunamazsa -1.
     */
    fun toggleGroupAtPosition(position: Int): Int {
        if (position < 0 || position >= displayItems.size) return -1

        var headerIndex = -1
        var groupName: String? = null
        for (i in position downTo 0) {
            val item = displayItems[i]
            if (item is ChannelListItem.Header) {
                headerIndex = i
                groupName = item.groupName
                break
            }
        }
        if (groupName == null || headerIndex == -1) return -1

        if (expandedGroups.contains(groupName)) {
            expandedGroups.remove(groupName)
        } else {
            expandedGroups.add(groupName)
        }
        deleteTargetPosition = -1
        rebuildDisplayList()
        notifyDataSetChanged()
        return headerIndex
    }

    override fun getItemViewType(position: Int): Int {
        return when (displayItems[position]) {
            is ChannelListItem.Header -> TYPE_HEADER
            is ChannelListItem.Row -> TYPE_CHANNEL
        }
    }

    override fun getItemCount() = displayItems.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val arrow: TextView = view.findViewById(R.id.tvGroupArrow)
        val name: TextView = view.findViewById(R.id.tvGroupName)
    }

    class ChannelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rootLayout: LinearLayout = view as LinearLayout
        val nameTextView: TextView = view.findViewById(R.id.tvChannelName)
        val deleteIcon: ImageView? = view.findViewById(R.id.ivDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_channel_group, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_channel, parent, false)
            ChannelViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = displayItems[position]) {
            is ChannelListItem.Header -> bindHeader(holder as HeaderViewHolder, item)
            is ChannelListItem.Row -> bindChannel(holder as ChannelViewHolder, item, position)
        }
    }

    private fun bindHeader(holder: HeaderViewHolder, header: ChannelListItem.Header) {
        val isExpanded = expandedGroups.contains(header.groupName)
        holder.arrow.text = if (isExpanded) "▼" else "▶"
        holder.name.text = header.groupName

        holder.itemView.setOnClickListener {
            val headerPos = holder.adapterPosition
            if (isExpanded) {
                expandedGroups.remove(header.groupName)
            } else {
                expandedGroups.add(header.groupName)
            }
            deleteTargetPosition = -1
            rebuildDisplayList()
            notifyDataSetChanged()

            // Başlık, açılıp/kapanmadan sonra da odakta kalsın
            if (headerPos != RecyclerView.NO_POSITION) {
                recyclerViewRef?.post {
                    recyclerViewRef?.layoutManager?.findViewByPosition(headerPos)?.requestFocus()
                }
            }
        }
    }

    private fun bindChannel(holder: ChannelViewHolder, row: ChannelListItem.Row, position: Int) {
        holder.nameTextView.text = row.channel.name

        if (position == deleteTargetPosition) {
            holder.rootLayout.setBackgroundColor(Color.parseColor("#B00020"))
            holder.deleteIcon?.visibility = View.VISIBLE
        } else {
            holder.rootLayout.setBackgroundResource(R.drawable.channel_item_background)
            holder.deleteIcon?.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos != RecyclerView.NO_POSITION && currentPos != deleteTargetPosition) {
                onClick(row.channel, row.channelIndex)
            }
        }

        holder.deleteIcon?.setOnClickListener {
            onDeleteClick(row.channelIndex)
        }

        holder.itemView.setOnKeyListener { _, keyCode, event ->
            val currentPos = holder.adapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnKeyListener false

            if (currentPos == deleteTargetPosition) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            onDeleteClick(row.channelIndex)
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            return@setOnKeyListener true
                        }
                        else -> {
                            resetDeleteState()
                            return@setOnKeyListener false
                        }
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        return@setOnKeyListener true
                    }
                }
            } else {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        rightKeyRunnable = Runnable {
                            deleteTargetPosition = currentPos
                            notifyItemChanged(currentPos)
                        }
                        handler.postDelayed(rightKeyRunnable!!, 2000)
                        return@setOnKeyListener true
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        rightKeyRunnable?.let { handler.removeCallbacks(it) }
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    fun resetDeleteState() {
        val oldPos = deleteTargetPosition
        deleteTargetPosition = -1
        if (oldPos != -1) {
            notifyItemChanged(oldPos)
        }
    }
}

/**
 * M3U kaynağını (URL veya cihaz dosyası) satır satır (streaming) okuyup kanal listesine çevirir.
 *
 * Önceki sürüme göre farklar:
 * - GlobalScope YERİNE çağıran taraf (lifecycleScope) coroutine'in ömrünü yönetiyor; bu fonksiyon
 *   sadece `suspend` ve IO thread'inde çalışıyor, Activity yok olursa otomatik iptal edilir.
 * - Tüm dosya tek seferde belleğe (.readText()) alınmıyor; BufferedReader.forEachLine ile
 *   satır satır okunuyor. Büyük M3U dosyalarında OutOfMemoryError riskini ortadan kaldırır.
 * - HttpURLConnection üzerinden açık connect/read timeout (15 sn) tanımlanıyor; sunucu yanıt
 *   vermezse sonsuza kadar beklemek yerine SocketTimeoutException fırlatılıyor.
 * - GitHub (raw.githubusercontent.com) gibi bazı CDN'ler, tarayıcıya benzemeyen ya da boş
 *   User-Agent'lı istekleri 403 ile reddedebiliyor; bu yüzden açık bir User-Agent header'ı
 *   ekleniyor. Ayrıca HTTP durum kodu kontrol edilip 200-299 dışında anlamlı bir hata fırlatılıyor.
 */
suspend fun parseM3u(context: Context, m3uSource: String): List<Channel> = withContext(Dispatchers.IO) {
    val channelList = mutableListOf<Channel>()

    val reader: BufferedReader = if (m3uSource.startsWith("content://") || m3uSource.startsWith("file://")) {
        val uri = Uri.parse(m3uSource)
        context.contentResolver.openInputStream(uri)?.bufferedReader()
            ?: throw IOException("Dosya açılamadı, izin geçersiz olabilir")
    } else {
        // Android 6.0 (API 23) gibi eski cihaz/emülatörlerde sistemin SSL kök sertifika deposu
        // güncel olmayabilir ("Trust anchor for certification path not found" hatası). Google Play
        // Services üzerinden SSL sağlayıcısını (Conscrypt) güncelletmeyi deniyoruz. Play Services
        // yoksa (bazı özel/ucuz TV kutuları) bu sessizce başarısız olur, akış normal devam eder.
        try {
            ProviderInstaller.installIfNeeded(context)
        } catch (e: GooglePlayServicesRepairableException) {
            Log.w("UrganTV_M3U", "Play Services güncellemesi gerekiyor, sertifika sağlayıcı güncellenemedi", e)
        } catch (e: GooglePlayServicesNotAvailableException) {
            Log.w("UrganTV_M3U", "Play Services mevcut değil, sertifika sağlayıcı güncellenemedi", e)
        } catch (e: Exception) {
            Log.w("UrganTV_M3U", "ProviderInstaller çalıştırılamadı", e)
        }

        val url = URL(m3uSource)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000 // 15 sn - bağlantı kurulamazsa bekleme
        connection.readTimeout = 15000    // 15 sn - sunucu veri göndermezse bekleme
        connection.instanceFollowRedirects = true
        // GitHub raw / bazı CDN'ler User-Agent olmadan veya "Java/x.x" ile 403 dönebiliyor.
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) UrganTV/1.0")
        connection.requestMethod = "GET"
        connection.connect()

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw IOException("Sunucu hata kodu döndürdü: $responseCode (link doğru mu, 'raw' link mi kontrol et)")
        }

        connection.inputStream.bufferedReader()
    }

    try {
        val groupTitleRegex = Regex("""group-title\s*=\s*"([^"]*)"""")
        var currentName = ""
        var currentGroup = "Diğer"
        reader.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:")) {
                val commaIndex = trimmed.lastIndexOf(',')
                if (commaIndex != -1) {
                    currentName = trimmed.substring(commaIndex + 1).trim()
                }
                currentGroup = groupTitleRegex.find(trimmed)
                    ?.groupValues?.get(1)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "Diğer"
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                if (currentName.isNotEmpty() && (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("rtsp://") || trimmed.startsWith("rtmp://"))) {
                    channelList.add(Channel(currentName, trimmed, currentGroup))
                }
                currentName = ""
                currentGroup = "Diğer"
            }
        }
    } finally {
        reader.close()
    }

    channelList
}