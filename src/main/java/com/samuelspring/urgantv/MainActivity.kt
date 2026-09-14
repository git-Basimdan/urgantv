package com.samuelspring.urgantv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

data class Channel(val name: String, val url: String)

class MainActivity : AppCompatActivity() {

    private val PREF_NAME = "IptvPrefs"
    private val KEY_M3U_URL = "m3u_url"
    private var player: ExoPlayer? = null
    private lateinit var recyclerViewChannels: RecyclerView
    private var channelList = mutableListOf<Channel>()
    private var currentPlayingIndex = -1
    private var channelAdapter: ChannelAdapter? = null

    private var doubleBackToExitPressedOnce = false
    private val leftKeyHandler = Handler(Looper.getMainLooper())
    private var leftKeyRunnable: Runnable? = null
    private var isLeftKeyPressed = false

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

        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    super.onPlayerError(error)
                    showCustomToast("Kanal açılmadı, sonraki geçiliyor...")

                    if (channelList.isNotEmpty() && currentPlayingIndex < channelList.size - 1) {
                        currentPlayingIndex++
                        playChannel(channelList[currentPlayingIndex].url)
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

        parseM3u(this, url) { loadedChannels ->
            if (loadedChannels.isNotEmpty()) {
                channelList = loadedChannels.toMutableList()
                showCustomToast("${channelList.size} kanal yüklendi!")

                val layoutManager = CenterLayoutManager(this)
                recyclerViewChannels.layoutManager = layoutManager

                channelAdapter = ChannelAdapter(
                    channelList,
                    onClick = { selectedChannel, position ->
                        currentPlayingIndex = position
                        playChannel(selectedChannel.url)
                        recyclerViewChannels.visibility = View.GONE
                    },
                    onDeleteClick = { position ->
                        showDeleteChannelDialog(position)
                    }
                )
                recyclerViewChannels.adapter = channelAdapter

            } else {
                showCustomToast("Kanallar okunamadı!")
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
                channelAdapter?.notifyItemRemoved(position)
                channelAdapter?.notifyItemRangeChanged(position, channelList.size)
                channelAdapter?.resetDeleteState()
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
                leftKeyRunnable = Runnable {
                    showM3uInputDialog()
                    showCustomToast("M3U Menüsü Açıldı")
                }
                leftKeyHandler.postDelayed(leftKeyRunnable!!, 3000)
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
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onBackPressed() {
        if (doubleBackToExitPressedOnce) {
            showExitConfirmationDialog()
            return
        }

        if (recyclerViewChannels.visibility == View.VISIBLE) {
            recyclerViewChannels.visibility = View.GONE
        } else {
            doubleBackToExitPressedOnce = true
            recyclerViewChannels.visibility = View.VISIBLE

            // Liste açıldığında çalan kanal ortada veya uygun sınırda görünsün
            if (currentPlayingIndex != -1) {
                recyclerViewChannels.scrollToPosition(currentPlayingIndex)
                recyclerViewChannels.post {
                    recyclerViewChannels.layoutManager?.findViewByPosition(currentPlayingIndex)?.requestFocus()
                }
            } else if (channelList.isNotEmpty()) {
                recyclerViewChannels.scrollToPosition(0)
                recyclerViewChannels.post {
                    recyclerViewChannels.layoutManager?.findViewByPosition(0)?.requestFocus()
                }
            }

            showCustomToast("Çıkmak için tekrar geri tuşuna basın")

            Handler(Looper.getMainLooper()).postDelayed({
                doubleBackToExitPressedOnce = false
            }, 2000)
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
        player?.release()
        player = null
    }
}

// Uçlarda normal kayan, ortada ise sabitleyen akıllı LayoutManager
class CenterLayoutManager(context: Context) : LinearLayoutManager(context) {
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

class ChannelAdapter(
    private val channels: List<Channel>,
    private val onClick: (Channel, Int) -> Unit,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    private var deleteTargetPosition = -1
    private val handler = Handler(Looper.getMainLooper())
    private var rightKeyRunnable: Runnable? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rootLayout: LinearLayout = view as LinearLayout
        val nameTextView: TextView = view.findViewById(R.id.tvChannelName)
        val deleteIcon: ImageView? = view.findViewById(R.id.ivDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        holder.nameTextView.text = channel.name

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
                onClick(channel, currentPos)
            }
        }

        holder.deleteIcon?.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onDeleteClick(currentPos)
            }
        }

        holder.itemView.setOnKeyListener { _, keyCode, event ->
            val currentPos = holder.adapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnKeyListener false

            if (currentPos == deleteTargetPosition) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            onDeleteClick(currentPos)
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
                        handler.postDelayed(rightKeyRunnable!!, 3000)
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

    override fun getItemCount() = channels.size
}

fun parseM3u(context: Context, m3uSource: String, onComplete: (List<Channel>) -> Unit) {
    GlobalScope.launch(Dispatchers.IO) {
        val channelList = mutableListOf<Channel>()
        try {
            val text = if (m3uSource.startsWith("content://") || m3uSource.startsWith("file://")) {
                val uri = Uri.parse(m3uSource)
                context.contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() } ?: ""
            } else {
                URL(m3uSource).readText()
            }

            val lines = text.split("\n")
            var currentName = ""

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXTINF:")) {
                    val commaIndex = trimmed.lastIndexOf(',')
                    if (commaIndex != -1) {
                        currentName = trimmed.substring(commaIndex + 1).trim()
                    }
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    if (currentName.isNotEmpty() && (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("rtsp://") || trimmed.startsWith("rtmp://"))) {
                        channelList.add(Channel(currentName, trimmed))
                    }
                    currentName = ""
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        withContext(Dispatchers.Main) {
            onComplete(channelList)
        }
    }
}