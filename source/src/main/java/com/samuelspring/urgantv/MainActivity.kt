package com.samuelspring.urgantv

import android.content.Context
import android.content.Intent
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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

data class Channel(
    val name: String,
    val url: String,
    val group: String = "",
    val logoUrl: String = "",
    val tvgId: String = "",
    val channelNo: String = "",
    val userAgentEnabled: Boolean = true,
    val proxyEnabled: Boolean = false,
    val proxyType: String = "HTTP", // "HTTP" veya "SOCKS5"
    val proxyHost: String = "",
    val proxyPort: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = ""
)

class MainActivity : AppCompatActivity() {

    private val PREF_NAME = "IptvPrefs"
    private val KEY_M3U_URL = "m3u_url"
    private val KEY_CHANNEL_LIST = "channel_list_json"
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

        // User-Agent artık her kanal için ayrı ayrı (kanalın kendi ayarına göre) uygulanıyor -
        // bkz. playChannel() ve buildMediaSourceFactory(). Player burada sade kuruluyor.
        player = ExoPlayer.Builder(this)
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
        // Önce daha önce kaydedilmiş (silme/düzenleme/taşıma dahil) kanal listesi var mı bak.
        // Varsa direkt onu kullan - her açılışta kaynaktan yeniden çekip değişiklikleri
        // kaybetmemek için. Sadece hiç kayıt yoksa (ilk kurulum) kaynaktan indirilir.
        val persisted = loadChannelListFromPrefs()
        if (persisted != null && persisted.isNotEmpty()) {
            channelList = persisted.toMutableList()
            showCustomToast("${channelList.size} kanal yüklendi (kayıtlı liste)")
            setupChannelListAdapter()
            return
        }

        val sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedUrl = sharedPreferences.getString(KEY_M3U_URL, null)

        if (savedUrl.isNullOrEmpty()) {
            showM3uInputDialog()
        } else {
            loadM3uData(savedUrl)
        }
    }

    /** Kanal listesini (silme, düzenleme, taşıma sonrası da dahil) cihaza kalıcı olarak kaydeder. */
    private fun saveChannelListToPrefs() {
        try {
            val jsonArray = JSONArray()
            for (ch in channelList) {
                val obj = JSONObject()
                obj.put("name", ch.name)
                obj.put("url", ch.url)
                obj.put("group", ch.group)
                obj.put("logoUrl", ch.logoUrl)
                obj.put("tvgId", ch.tvgId)
                obj.put("channelNo", ch.channelNo)
                obj.put("userAgentEnabled", ch.userAgentEnabled)
                obj.put("proxyEnabled", ch.proxyEnabled)
                obj.put("proxyType", ch.proxyType)
                obj.put("proxyHost", ch.proxyHost)
                obj.put("proxyPort", ch.proxyPort)
                obj.put("proxyUsername", ch.proxyUsername)
                obj.put("proxyPassword", ch.proxyPassword)
                jsonArray.put(obj)
            }
            getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CHANNEL_LIST, jsonArray.toString())
                .apply()
        } catch (e: Exception) {
            Log.e("UrganTV_Prefs", "Kanal listesi kaydedilemedi", e)
        }
    }

    /** Daha önce kaydedilmiş kanal listesini okur. Kayıt yoksa/bozuksa null döner. */
    private fun loadChannelListFromPrefs(): List<Channel>? {
        val json = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CHANNEL_LIST, null) ?: return null
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Channel(
                    name = obj.optString("name", ""),
                    url = obj.optString("url", ""),
                    group = obj.optString("group", ""),
                    logoUrl = obj.optString("logoUrl", ""),
                    tvgId = obj.optString("tvgId", ""),
                    channelNo = obj.optString("channelNo", ""),
                    userAgentEnabled = obj.optBoolean("userAgentEnabled", true),
                    proxyEnabled = obj.optBoolean("proxyEnabled", false),
                    proxyType = obj.optString("proxyType", "HTTP"),
                    proxyHost = obj.optString("proxyHost", ""),
                    proxyPort = obj.optString("proxyPort", ""),
                    proxyUsername = obj.optString("proxyUsername", ""),
                    proxyPassword = obj.optString("proxyPassword", "")
                )
            }
        } catch (e: Exception) {
            Log.e("UrganTV_Prefs", "Kayıtlı kanal listesi okunamadı", e)
            null
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
                    setupChannelListAdapter()
                    // Kaynaktan taze çekilen liste, bir sonraki açılışta da aynen çıksın diye
                    // (üzerine yapılacak silme/düzenleme/taşımalarla birlikte) kaydediliyor.
                    saveChannelListToPrefs()
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

    /**
     * channelList (network'ten ya da kayıtlı listeden) hazır olduktan sonra RecyclerView'ı
     * kurar. Hem loadM3uData (ağdan yükleme) hem de checkM3uUrl (kayıtlı listeyi açma)
     * tarafından ortak kullanılır.
     */
    private fun setupChannelListAdapter() {
        val layoutManager = CenterLayoutManager(this)
        recyclerViewChannels.layoutManager = layoutManager

        channelAdapter = ChannelAdapter(
            channelList,
            onClick = { selectedChannel, position ->
                currentPlayingIndex = position
                playChannel(selectedChannel)
                // Liste kapanmıyor; sadece 15 sn'lik hareketsizlik sayacı sıfırlanıyor.
                scheduleListAutoHide()
            },
            onOpenMenu = { channelIndex ->
                showChannelContextMenu(channelIndex)
            }
        )
        recyclerViewChannels.adapter = channelAdapter
        // Liste başlangıçta görünür durumda (XML'de visibility="visible"), o yüzden
        // ilk yüklemede de 15 sn'lik otomatik kapanma sayacı başlatılıyor.
        scheduleListAutoHide()

        recyclerViewChannels.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                // Grup başlığı ve kanal satırı farklı yükseklikte olabildiği için,
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
    }

    /**
     * Sağ tuşa basınca açılan yan menü: kanalı sil, düzenle, ya da liste görünümünü
     * (gruplu/grupsuz) değiştir.
     */
    private fun showChannelContextMenu(channelIndex: Int) {
        if (channelIndex < 0 || channelIndex >= channelList.size) return
        val channel = channelList[channelIndex]
        val isGroupingOn = channelAdapter?.isGroupingEnabled() ?: false

        val options = arrayOf(
            "Kanalı Sil",
            "Kanalı Düzenle",
            "Kanal Ekle",
            "Kanal Ara",
            if (isGroupingOn) "Liste Görünümü: Grupsuz Yap" else "Liste Görünümü: Gruplu Yap",
            if (channel.userAgentEnabled) "User Agent: Kapat" else "User Agent: Aç (Chrome)",
            if (channel.proxyEnabled) "Proxy Ayarları (Açık)" else "Proxy Ayarları (Kapalı)",
            "Kapat"
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(channel.name)
            .setItems(options) { d, which ->
                when (which) {
                    0 -> showDeleteChannelDialog(channelIndex)
                    1 -> showEditChannelDialog(channelIndex)
                    2 -> showAddChannelDialog(channelIndex)
                    3 -> showSearchChannelDialog()
                    4 -> toggleGroupView()
                    5 -> toggleChannelUserAgent(channelIndex)
                    6 -> showProxySettingsDialog(channelIndex)
                    7 -> { /* Kapat: sadece menüyü kapat */ }
                }
                d.dismiss()
            }
            .setCancelable(true)
            .create()

        // Bazı TV kumandalarında/emülatörlerde geri tuşu diyaloğun varsayılan kapatma
        // davranışını tetiklemeyebiliyor; bu yüzden geri tuşunu burada garanti altına alıp
        // doğrudan kapatıyoruz.
        dialog.setOnKeyListener { d, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                d.dismiss()
                true
            } else {
                false
            }
        }

        dialog.show()
    }

    private fun toggleGroupView() {
        val newState = channelAdapter?.toggleGrouping() ?: return
        showCustomToast(if (newState) "Grup görünümü açık" else "Grup görünümü kapalı")
    }

    /**
     * Bu kanalın User-Agent ayarını açar/kapatır. Açıkken Chrome User-Agent'ı gönderilir,
     * kapalıyken User-Agent hiç gönderilmez (silinir). Bu, kanala özel bir ayardır - diğer
     * kanalları etkilemez. Eğer o an oynatılan kanal buysa, değişikliğin hemen etkili olması
     * için yayın anında yeniden başlatılır.
     */
    private fun toggleChannelUserAgent(channelIndex: Int) {
        if (channelIndex < 0 || channelIndex >= channelList.size) return
        val channel = channelList[channelIndex]
        val updated = channel.copy(userAgentEnabled = !channel.userAgentEnabled)
        channelList[channelIndex] = updated
        persistChannelListChange()

        showCustomToast(
            if (updated.userAgentEnabled) "User Agent açık (Chrome)" else "User Agent kapalı"
        )

        // Şu an oynayan kanal buysa, ayarın etkili olması için yayını yeniden başlat.
        if (currentPlayingIndex == channelIndex) {
            playChannel(updated)
        }
    }

    /**
     * Kanala özel proxy ayarları: aç/kapa, tür (HTTP/SOCKS5), adres, port, kullanıcı adı/şifre.
     * Sadece bu kanalın bağlantısını etkiler, diğer kanalları etkilemez.
     */
    private fun showProxySettingsDialog(channelIndex: Int) {
        if (channelIndex < 0 || channelIndex >= channelList.size) return
        val channel = channelList[channelIndex]

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val enabledCheck = CheckBox(this).apply {
            text = "Bu kanal için proxy kullan"
            isChecked = channel.proxyEnabled
        }

        val typeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val httpRadio = RadioButton(this).apply { id = View.generateViewId(); text = "HTTP" }
        val socksRadio = RadioButton(this).apply { id = View.generateViewId(); text = "SOCKS5" }
        typeGroup.addView(httpRadio)
        typeGroup.addView(socksRadio)
        if (channel.proxyType.equals("SOCKS5", ignoreCase = true)) {
            socksRadio.isChecked = true
        } else {
            httpRadio.isChecked = true
        }

        val hostInput = EditText(this).apply {
            hint = "Proxy adresi (host)"
            setText(channel.proxyHost)
        }
        val portInput = EditText(this).apply {
            hint = "Port"
            setText(channel.proxyPort)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val userInput = EditText(this).apply {
            hint = "Kullanıcı adı (opsiyonel)"
            setText(channel.proxyUsername)
        }
        val passInput = EditText(this).apply {
            hint = "Şifre (opsiyonel)"
            setText(channel.proxyPassword)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        container.addView(enabledCheck)
        container.addView(typeGroup)
        container.addView(hostInput)
        container.addView(portInput)
        container.addView(userInput)
        container.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle("Proxy Ayarları - ${channel.name}")
            .setView(container)
            .setPositiveButton("Kaydet") { dialog, _ ->
                val updated = channel.copy(
                    proxyEnabled = enabledCheck.isChecked,
                    proxyType = if (socksRadio.isChecked) "SOCKS5" else "HTTP",
                    proxyHost = hostInput.text.toString().trim(),
                    proxyPort = portInput.text.toString().trim(),
                    proxyUsername = userInput.text.toString().trim(),
                    proxyPassword = passInput.text.toString()
                )

                if (updated.proxyEnabled && (updated.proxyHost.isBlank() || updated.proxyPort.toIntOrNull() == null)) {
                    showCustomToast("Proxy açık ama adres/port geçersiz - kontrol et")
                }

                channelList[channelIndex] = updated
                persistChannelListChange()
                showCustomToast(if (updated.proxyEnabled) "Proxy açık" else "Proxy kapalı")

                // Şu an oynayan kanal buysa, ayarın etkili olması için yayını yeniden başlat.
                if (currentPlayingIndex == channelIndex) {
                    playChannel(updated)
                }
                dialog.dismiss()
            }
            .setNegativeButton("İptal") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Kanal arama: yazdıkça sonuçlar diyaloğun kendi içindeki ayrı bir listede
     * (arama kutusunun altında) anlık güncellenir. Ana kanal listesi HİÇ değişmez/filtrelenmez.
     * Sonuçlarda yukarı/aşağı ile gezilip OK ile seçilince o kanala geçilir, ana listede de
     * o kanala odaklanılır ve diyalog kapanır.
     */
    private fun showSearchChannelDialog() {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val input = EditText(this).apply {
            hint = "Kanal adı ile ara..."
        }

        val resultsAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        val resultsList = ListView(this).apply {
            adapter = resultsAdapter
        }

        // Sonuç listesinde gösterilen her satırın, gerçek channelList içindeki index'i.
        var matchedIndices: List<Int> = emptyList()

        fun updateResults(query: String) {
            matchedIndices = if (query.isBlank()) {
                emptyList()
            } else {
                channelList.indices.filter { channelList[it].name.contains(query, ignoreCase = true) }
            }
            resultsAdapter.clear()
            resultsAdapter.addAll(matchedIndices.map { channelList[it].name })
            resultsAdapter.notifyDataSetChanged()
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateResults(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        container.addView(input)
        container.addView(
            resultsList,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (280 * density).toInt())
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("Kanal Ara")
            .setView(container)
            .setNegativeButton("Kapat") { d, _ -> d.dismiss() }
            .create()

        resultsList.setOnItemClickListener { _, _, position, _ ->
            val channelIndex = matchedIndices.getOrNull(position) ?: return@setOnItemClickListener
            currentPlayingIndex = channelIndex
            playChannel(channelList[channelIndex])

            // Ana listede de o kanala git (grubu kapalıysa açar) ve odaklan.
            val adapterPos = channelAdapter?.ensureChannelVisibleAndGetPosition(channelIndex) ?: -1
            if (adapterPos != -1) {
                recyclerViewChannels.scrollToPosition(adapterPos)
                recyclerViewChannels.post {
                    recyclerViewChannels.layoutManager?.findViewByPosition(adapterPos)?.requestFocus()
                }
            }
            dialog.dismiss()
        }

        // Bazı TV kumandalarında/emülatörlerde geri tuşu diyaloğun varsayılan kapatma
        // davranışını tetiklemeyebiliyor; burada garanti altına alıyoruz.
        dialog.setOnKeyListener { d, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                d.dismiss()
                true
            } else {
                false
            }
        }

        dialog.show()
    }

    private fun showEditChannelDialog(initialIndex: Int) {
        if (initialIndex < 0 || initialIndex >= channelList.size) return
        // Yukarı/aşağı taşıma sırasında kanalın gerçek index'i değişir; diyalog
        // içinde bunu takip etmek için ayrı bir değişkende tutuyoruz.
        var currentIndex = initialIndex
        val channel = channelList[currentIndex]

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val nameInput = EditText(this).apply { hint = "Kanal adı"; setText(channel.name) }
        val urlInput = EditText(this).apply { hint = "Yayın URL'si"; setText(channel.url) }
        val groupInput = EditText(this).apply { hint = "Grup / Kategori"; setText(channel.group) }
        val logoInput = EditText(this).apply { hint = "Logo URL"; setText(channel.logoUrl) }
        val idInput = EditText(this).apply { hint = "Kanal ID (tvg-id)"; setText(channel.tvgId) }
        val noInput = EditText(this).apply { hint = "Kanal No"; setText(channel.channelNo) }

        val moveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, padding, 0, 0)
        }
        val moveUpBtn = Button(this).apply { text = "▲ Yukarı Taşı" }
        val moveDownBtn = Button(this).apply { text = "▼ Aşağı Taşı" }
        moveRow.addView(moveUpBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        moveRow.addView(moveDownBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        moveUpBtn.setOnClickListener {
            if (currentIndex > 0) {
                val tmp = channelList[currentIndex]
                channelList[currentIndex] = channelList[currentIndex - 1]
                channelList[currentIndex - 1] = tmp
                currentIndex--
                persistChannelListChange()
                showCustomToast("Kanal yukarı taşındı")
            }
        }
        moveDownBtn.setOnClickListener {
            if (currentIndex < channelList.size - 1) {
                val tmp = channelList[currentIndex]
                channelList[currentIndex] = channelList[currentIndex + 1]
                channelList[currentIndex + 1] = tmp
                currentIndex++
                persistChannelListChange()
                showCustomToast("Kanal aşağı taşındı")
            }
        }

        container.addView(nameInput)
        container.addView(urlInput)
        container.addView(groupInput)
        container.addView(logoInput)
        container.addView(idInput)
        container.addView(noInput)
        container.addView(moveRow)

        AlertDialog.Builder(this)
            .setTitle("Kanalı Düzenle")
            .setView(container)
            .setPositiveButton("Kaydet") { dialog, _ ->
                val newName = nameInput.text.toString().trim()
                val newUrl = urlInput.text.toString().trim()
                if (newName.isNotEmpty() && newUrl.isNotEmpty()) {
                    // Diğer alanlar boş bırakılabilir; zorla varsayılan değer atanmaz.
                    channelList[currentIndex] = Channel(
                        name = newName,
                        url = newUrl,
                        group = groupInput.text.toString().trim(),
                        logoUrl = logoInput.text.toString().trim(),
                        tvgId = idInput.text.toString().trim(),
                        channelNo = noInput.text.toString().trim()
                    )
                    persistChannelListChange()
                    showCustomToast("Kanal güncellendi")
                } else {
                    showCustomToast("Kanal adı ve URL boş olamaz")
                }
                dialog.dismiss()
            }
            .setNegativeButton("İptal") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Yeni bir kanalı, menünün açıldığı kanalın (afterIndex) hemen altına ekler.
     * Alanlar boş başlar; sadece ad ve URL zorunludur, diğerleri boş bırakılabilir.
     */
    private fun showAddChannelDialog(afterIndex: Int) {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val nameInput = EditText(this).apply { hint = "Kanal adı" }
        val urlInput = EditText(this).apply { hint = "Yayın URL'si" }
        val groupInput = EditText(this).apply { hint = "Grup / Kategori" }
        val logoInput = EditText(this).apply { hint = "Logo URL" }
        val idInput = EditText(this).apply { hint = "Kanal ID (tvg-id)" }
        val noInput = EditText(this).apply { hint = "Kanal No" }

        container.addView(nameInput)
        container.addView(urlInput)
        container.addView(groupInput)
        container.addView(logoInput)
        container.addView(idInput)
        container.addView(noInput)

        AlertDialog.Builder(this)
            .setTitle("Kanal Ekle")
            .setView(container)
            .setPositiveButton("Ekle") { dialog, _ ->
                val newName = nameInput.text.toString().trim()
                val newUrl = urlInput.text.toString().trim()
                if (newName.isNotEmpty() && newUrl.isNotEmpty()) {
                    val newChannel = Channel(
                        name = newName,
                        url = newUrl,
                        group = groupInput.text.toString().trim(),
                        logoUrl = logoInput.text.toString().trim(),
                        tvgId = idInput.text.toString().trim(),
                        channelNo = noInput.text.toString().trim()
                    )
                    val insertIndex = (afterIndex + 1).coerceIn(0, channelList.size)
                    channelList.add(insertIndex, newChannel)
                    // Araya ekleme, o andan sonraki index'leri kaydırır; çalan kanalın
                    // takibini bozmamak için gerekirse currentPlayingIndex'i de kaydır.
                    if (currentPlayingIndex != -1 && insertIndex <= currentPlayingIndex) {
                        currentPlayingIndex++
                    }
                    persistChannelListChange()
                    showCustomToast("Kanal eklendi")
                } else {
                    showCustomToast("Kanal adı ve URL boş olamaz")
                }
                dialog.dismiss()
            }
            .setNegativeButton("İptal") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showDeleteChannelDialog(position: Int) {
        if (position < 0 || position >= channelList.size) return
        val channelNameToDelete = channelList[position].name

        AlertDialog.Builder(this)
            .setTitle("Kanalı Sil")
            .setMessage("Emin misiniz?\n\n'$channelNameToDelete' kanalını listeden kaldırmak istiyor musunuz?")
            .setPositiveButton("Evet, Sil") { _, _ ->
                channelList.removeAt(position)
                persistChannelListChange()
                showCustomToast("Kanal silindi")
            }
            .setNegativeButton("İptal") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * channelList her değiştiğinde (silme, düzenleme, taşıma) hem adapter'ı hem de
     * kalıcı kaydı günceller ki uygulama kapanıp açılınca değişiklikler kaybolmasın.
     */
    private fun persistChannelListChange() {
        channelAdapter?.refresh()
        saveChannelListToPrefs()
    }

    private val CHROME_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Kanalın kendi ayarlarına (User-Agent açık/kapalı, proxy açık/kapalı) göre bir
     * DataSource.Factory kurar. Proxy kapalıysa ExoPlayer'ın kendi varsayılan HTTP
     * katmanı (DefaultHttpDataSource) kullanılır. Proxy açıksa, ExoPlayer'ın varsayılan
     * katmanı proxy desteklemediği için OkHttp tabanlı bir katmana geçilir.
     */
    private fun buildMediaSourceFactory(channel: Channel): DefaultMediaSourceFactory {
        val dataSourceFactory: DataSource.Factory =
            if (channel.proxyEnabled && channel.proxyHost.isNotBlank() && channel.proxyPort.toIntOrNull() != null) {
                buildProxyDataSourceFactory(channel)
            } else {
                DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(15000)
                    .apply {
                        if (channel.userAgentEnabled) {
                            setUserAgent(CHROME_USER_AGENT)
                        }
                    }
            }

        return DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
    }

    /**
     * OkHttp ile HTTP ya da SOCKS5 proxy üzerinden bağlanan bir DataSource.Factory kurar.
     * HTTP proxy'de kullanıcı adı/şifre verilmişse sunucu 407 dönünce OkHttp otomatik
     * Basic Auth ekler (bağlantıya özeldir, başka isteği etkilemez).
     * SOCKS5'te kimlik doğrulama Java'nın kendi Authenticator mekanizmasıyla yapılır;
     * bu JVM genelinde geçerlidir (uygulama aynı anda tek kanal oynattığı için pratikte
     * sorun yaratmaz, ama bilinmesi gereken bir sınırlamadır).
     */
    private fun buildProxyDataSourceFactory(channel: Channel): DataSource.Factory {
        val port = channel.proxyPort.toIntOrNull() ?: 0
        val isSocks = channel.proxyType.equals("SOCKS5", ignoreCase = true)
        val proxyType = if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP
        val proxy = Proxy(proxyType, InetSocketAddress(channel.proxyHost, port))

        val clientBuilder = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)

        if (channel.proxyUsername.isNotBlank()) {
            if (isSocks) {
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(channel.proxyUsername, channel.proxyPassword.toCharArray())
                    }
                })
            } else {
                clientBuilder.proxyAuthenticator { _, response ->
                    val credential = Credentials.basic(channel.proxyUsername, channel.proxyPassword)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
        }

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(clientBuilder.build())
        if (channel.userAgentEnabled) {
            okHttpDataSourceFactory.setUserAgent(CHROME_USER_AGENT)
        }
        return okHttpDataSourceFactory
    }

    private fun playChannel(channel: Channel) {
        player?.let {
            try {
                val mediaItem = MediaItem.fromUri(channel.url)
                val mediaSource = buildMediaSourceFactory(channel).createMediaSource(mediaItem)
                it.setMediaSource(mediaSource)
                it.prepare()
                it.play()
            } catch (e: Exception) {
                Log.e("UrganTV_Player", "Kanal oynatılamadı: ${channel.url}", e)
                showCustomToast("Yayın yüklenirken hata oluştu: ${e.message}")
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
        // Liste kapalıyken (tam ekran oynatıcı) yukarı/aşağı ile kanal değişimini burada,
        // PlayerView'e hiç ulaşmadan yakalıyoruz. Aksi halde PlayerView ilk basışı kendi
        // oynatıcı kontrollerini (play/pause vb.) göstermek için tüketiyordu ve kanal
        // değişimi ancak ikinci basışta gerçekleşiyordu. OK/merkez tuşuna dokunmuyoruz,
        // o hâlâ normal şekilde PlayerView'e gidip kontrolleri gösterip gizliyor.
        if (recyclerViewChannels.visibility == View.GONE &&
            channelList.isNotEmpty() &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    // Sondaysa başa dön (döngüsel)
                    currentPlayingIndex = if (currentPlayingIndex < channelList.size - 1) {
                        currentPlayingIndex + 1
                    } else {
                        0
                    }
                    playChannel(channelList[currentPlayingIndex])
                    showCustomToast(channelList[currentPlayingIndex].name)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    // Baştaysa sona dön (döngüsel)
                    currentPlayingIndex = if (currentPlayingIndex > 0) {
                        currentPlayingIndex - 1
                    } else {
                        channelList.size - 1
                    }
                    playChannel(channelList[currentPlayingIndex])
                    showCustomToast(channelList[currentPlayingIndex].name)
                    return true
                }
            }
        }

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

    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToWindow(view: RecyclerView?) {
        super.onAttachedToWindow(view)
        attachedRecyclerView = view
    }

    override fun onDetachedFromWindow(view: RecyclerView?, recycler: RecyclerView.Recycler?) {
        super.onDetachedFromWindow(view, recycler)
        attachedRecyclerView = null
    }

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

    // Liste sonunda aşağı ya da başında yukarı basıldığında normalde fokus hareketi
    // başarısız olur (gidecek yer yoktur). Burada bunu yakalayıp listeyi döngüsel
    // yapıyoruz: sondan aşağı -> başa, baştan yukarı -> sona.
    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        val count = itemCount
        if (count == 0) return super.onFocusSearchFailed(focused, focusDirection, recycler, state)

        val targetPos = when (focusDirection) {
            View.FOCUS_DOWN -> 0
            View.FOCUS_UP -> count - 1
            else -> return super.onFocusSearchFailed(focused, focusDirection, recycler, state)
        }

        val rv = attachedRecyclerView
        if (rv != null) {
            scrollToPosition(targetPos)
            rv.post {
                rv.layoutManager?.findViewByPosition(targetPos)?.requestFocus()
            }
        }
        // View henüz layout'a girmediği için bu turda senkron döndüremiyoruz;
        // yukarıdaki post ile bir sonraki frame'de odak gerçek hedefe taşınıyor.
        return null
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
    private val onOpenMenu: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CHANNEL = 1
    }

    // Grup görünümü artık menüden açılıp kapatılabiliyor; varsayılan kapalı (düz liste).
    private var groupingEnabled = false

    // Hangi grupların açık (expanded) olduğunu tutar. Akordeon mantığı: aynı anda sadece
    // TEK bir grup açık olabilir (bkz. expandOnlyThisGroup). Başlangıçta hepsi kapalı -
    // böylece grup görünümüne geçince önce sadece başlıklar görünür, yukarı/aşağı da
    // başlıklar arasında gezinir.
    private val expandedGroups = mutableSetOf<String>()

    private var displayItems: List<ChannelListItem> = emptyList()
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

    // Ana liste artık ASLA filtrelenmiyor - arama, kendi diyaloğunun içinde ayrı bir
    // sonuç listesinde gösteriliyor (bkz. MainActivity.showSearchChannelDialog). Bu sayede
    // ana liste her zaman tam kalır, "sadece bulunanlar görünüyor, geri dönemiyoruz" sorunu
    // kökten ortadan kalkar.
    private fun rebuildDisplayList() {
        if (!groupingEnabled) {
            displayItems = channels.indices.map { idx -> ChannelListItem.Row(channels[idx], idx) }
            return
        }

        val items = mutableListOf<ChannelListItem>()
        val groupToIndices = LinkedHashMap<String, MutableList<Int>>()
        channels.indices.forEach { idx ->
            groupToIndices.getOrPut(channels[idx].group.ifBlank { "Diğer" }) { mutableListOf() }.add(idx)
        }
        for ((groupName, indices) in groupToIndices) {
            items.add(ChannelListItem.Header(groupName))
            if (expandedGroups.contains(groupName)) {
                indices.forEach { idx -> items.add(ChannelListItem.Row(channels[idx], idx)) }
            }
        }
        displayItems = items
    }

    /** channelList değiştiğinde (örn. silme, düzenleme) MainActivity'nin çağırması gereken metod. */
    fun refresh() {
        rebuildDisplayList()
        notifyDataSetChanged()
    }

    fun isGroupingEnabled(): Boolean = groupingEnabled

    /** Yan menüden çağrılır: gruplu/grupsuz görünüm arasında geçiş yapar. Yeni durumu döner. */
    fun toggleGrouping(): Boolean {
        groupingEnabled = !groupingEnabled
        // Görünüm her değiştiğinde temiz başlasın: hepsi kapalı, sadece başlıklar görünür.
        expandedGroups.clear()
        rebuildDisplayList()
        notifyDataSetChanged()
        return groupingEnabled
    }

    /**
     * Akordeon mantığı: verilen grubu açar, o sırada açık olan BAŞKA bütün grupları kapatır.
     * Böylece aynı anda sadece bir grubun kanalları görünür; bir grubun kanalları bitince
     * yukarı/aşağı direkt bir sonraki grubun başlığına geçer.
     */
    private fun expandOnlyThisGroup(groupName: String) {
        expandedGroups.clear()
        expandedGroups.add(groupName)
    }

    /**
     * Verilen gerçek kanal index'inin grubu kapalıysa açar (akordeon: diğerlerini kapatarak),
     * sonra bu kanalın o anki flat (adapter) pozisyonunu döner. Kanal bulunamazsa -1 döner.
     */
    fun ensureChannelVisibleAndGetPosition(channelIndex: Int): Int {
        if (channelIndex < 0 || channelIndex >= channels.size) return -1
        val group = channels[channelIndex].group.ifBlank { "Diğer" }
        if (!expandedGroups.contains(group)) {
            expandOnlyThisGroup(group)
            rebuildDisplayList()
            notifyDataSetChanged()
        }
        return displayItems.indexOfFirst { it is ChannelListItem.Row && it.channelIndex == channelIndex }
    }

    /**
     * Verilen adapter pozisyonunun ait olduğu grubu açar/kapatır (akordeon: açarken diğer
     * tüm gruplar otomatik kapanır). Pozisyondaki satır bir kanal (Row) ise, geriye doğru
     * en yakın grup başlığı (Header) bulunup o kullanılır. Yeni odaklanılması gereken
     * başlığın pozisyonunu döner, bulunamazsa -1.
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
            expandOnlyThisGroup(groupName)
        }
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
                expandOnlyThisGroup(header.groupName)
            }
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
        holder.rootLayout.setBackgroundResource(R.drawable.channel_item_background)
        holder.deleteIcon?.visibility = View.GONE

        holder.itemView.setOnClickListener {
            onClick(row.channel, row.channelIndex)
        }

        // Sağ tuş artık basılı tutmaya gerek kalmadan, tek basışta yan menüyü açar:
        // Kanalı Sil / Kanalı Düzenle / Liste Görünümü (gruplu-grupsuz).
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0
            ) {
                onOpenMenu(row.channelIndex)
                true
            } else {
                false
            }
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
        val tvgIdRegex = Regex("""tvg-id\s*=\s*"([^"]*)"""")
        val tvgLogoRegex = Regex("""tvg-logo\s*=\s*"([^"]*)"""")
        val tvgChnoRegex = Regex("""tvg-chno\s*=\s*"([^"]*)"""")

        var currentName = ""
        var currentGroup = ""
        var currentLogo = ""
        var currentTvgId = ""
        var currentChno = ""

        fun extractAttr(regex: Regex, line: String): String =
            regex.find(line)?.groupValues?.get(1)?.trim().orEmpty()

        reader.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:")) {
                val commaIndex = trimmed.lastIndexOf(',')
                if (commaIndex != -1) {
                    currentName = trimmed.substring(commaIndex + 1).trim()
                }
                // M3U'da bu alanlar yoksa boş bırakılır, zorla varsayılan değer atanmaz.
                currentGroup = extractAttr(groupTitleRegex, trimmed)
                currentLogo = extractAttr(tvgLogoRegex, trimmed)
                currentTvgId = extractAttr(tvgIdRegex, trimmed)
                currentChno = extractAttr(tvgChnoRegex, trimmed)
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                if (currentName.isNotEmpty() && (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("rtsp://") || trimmed.startsWith("rtmp://"))) {
                    channelList.add(
                        Channel(
                            name = currentName,
                            url = trimmed,
                            group = currentGroup,
                            logoUrl = currentLogo,
                            tvgId = currentTvgId,
                            channelNo = currentChno
                        )
                    )
                }
                currentName = ""
                currentGroup = ""
                currentLogo = ""
                currentTvgId = ""
                currentChno = ""
            }
        }
    } finally {
        reader.close()
    }

    channelList
}