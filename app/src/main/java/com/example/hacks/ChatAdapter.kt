package com.example.hacks

import android.content.Intent
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class ChatAdapter(private val messages: MutableList<ChatMessage>, private val myUsername: String) :
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val VIEW_TYPE_ME = 1
    private val VIEW_TYPE_OTHER = 2
    
    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingPath: String? = null
    private var lastPlayedPosition: Int = -1

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvTimer: TextView = view.findViewById(R.id.tvTimer)
        val layoutAudio: LinearLayout = view.findViewById(R.id.layoutAudio)
        val btnPlayPause: ImageButton = view.findViewById(R.id.btnPlayPause)
        val tvAudioStatus: TextView = view.findViewById(R.id.tvAudioStatus)
        val layoutFile: LinearLayout = view.findViewById(R.id.layoutFile)
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isMe) VIEW_TYPE_ME else VIEW_TYPE_OTHER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layout = if (viewType == VIEW_TYPE_ME) R.layout.item_message_me else R.layout.item_message_other
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        
        // Reset visibility
        holder.tvMessage.visibility = View.GONE
        holder.layoutAudio.visibility = View.GONE
        holder.layoutFile.visibility = View.GONE

        when {
            message.isAudio -> {
                holder.layoutAudio.visibility = View.VISIBLE
                val isPlaying = currentlyPlayingPath == message.audioPath && mediaPlayer?.isPlaying == true
                holder.btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                holder.tvAudioStatus.text = if (isPlaying) "Playing..." else "Voice Note"
                holder.btnPlayPause.setOnClickListener {
                    toggleAudio(message.audioPath, holder, position)
                }
            }
            message.isFile -> {
                holder.layoutFile.visibility = View.VISIBLE
                holder.tvFileName.text = message.fileName
                holder.layoutFile.setOnClickListener {
                    openFile(message.filePath, holder.itemView.context)
                }
            }
            else -> {
                holder.tvMessage.visibility = View.VISIBLE
                holder.tvMessage.text = message.text
            }
        }
        
        holder.tvSender.text = message.sender

        if (message.expiryTime > 0) {
            holder.tvTimer.visibility = View.VISIBLE
            if (!message.timerStarted) {
                message.timerStarted = true
                startCountdown(holder, position, message.expiryTime)
            }
        } else {
            holder.tvTimer.visibility = View.GONE
        }
    }

    private fun toggleAudio(path: String?, holder: MessageViewHolder, position: Int) {
        if (path == null) return
        if (currentlyPlayingPath == path) {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                holder.btnPlayPause.setImageResource(R.drawable.ic_play)
                holder.tvAudioStatus.text = "Paused"
            } else {
                mediaPlayer?.start()
                holder.btnPlayPause.setImageResource(R.drawable.ic_pause)
                holder.tvAudioStatus.text = "Playing..."
            }
        } else {
            stopAudio()
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                    start()
                    setOnCompletionListener {
                        stopAudio()
                        notifyItemChanged(position)
                    }
                }
                currentlyPlayingPath = path
                val oldPos = lastPlayedPosition
                lastPlayedPosition = position
                if (oldPos != -1) notifyItemChanged(oldPos)
                notifyItemChanged(position)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun stopAudio() {
        mediaPlayer?.release()
        mediaPlayer = null
        currentlyPlayingPath = null
    }

    private fun openFile(path: String?, context: android.content.Context) {
        if (path == null) return
        try {
            val file = File(path)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "*/*"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Share/Open File"))
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(context, "Cannot open file", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCountdown(holder: MessageViewHolder, position: Int, seconds: Int) {
        val handler = Handler(Looper.getMainLooper())
        var remaining = seconds
        val runnable = object : Runnable {
            override fun run() {
                if (remaining > 0) {
                    holder.tvTimer.text = "${remaining}s"
                    remaining--
                    handler.postDelayed(this, 1000)
                } else {
                    if (position < messages.size) {
                        messages.removeAt(position)
                        notifyItemRemoved(position)
                        notifyItemRangeChanged(position, messages.size)
                    }
                }
            }
        }
        handler.post(runnable)
    }

    override fun getItemCount() = messages.size
}
