package com.example.hacks

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val messages: MutableList<ChatMessage>, private val myUsername: String) :
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val VIEW_TYPE_ME = 1
    private val VIEW_TYPE_OTHER = 2

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvTimer: TextView = view.findViewById(R.id.tvTimer)
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
        holder.tvMessage.text = message.text
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
                    // Final wipe
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
