package com.pcapremote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.*
import kotlinx.android.synthetic.main.fragment_log.*
import kotlinx.android.synthetic.main.log_item.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

@Entity
data class SslErrorEntry(val text: String, @PrimaryKey(autoGenerate = true) var id: Int = 0)

@Dao
interface SslErrorsDao {
    @Query("select * from SslErrorEntry order by id desc")
    fun all(): List<SslErrorEntry>

    @Insert
    fun add(sslErrorEntry: SslErrorEntry): Long

    @Query("delete from SslErrorEntry")
    fun deleteAll()

    @Query("select count(*) from SslErrorEntry")
    fun size(): Int

    @Query("delete from SslErrorEntry where id in " + "(select id from SslErrorEntry order by id asc limit :limit)")
    fun deleteLastEntries(limit: Int)
}

@Database(entities = [SslErrorEntry::class], version = 1)
abstract class SslErrorsDatabase : RoomDatabase() {
    abstract fun sslErrorDao(): SslErrorsDao
}

class SslErrorsFragment : Fragment() {
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private val accessLogAdapter = object : RecyclerView.Adapter<ViewHolder>() {
        var entries = mutableListOf<SslErrorEntry>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(
                    R.layout.log_item, parent, false)

            return ViewHolder(v)
        }

        override fun getItemCount(): Int {
            return entries.size
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = entries[position]
            holder.itemView.tvText.text = item.text

            val color = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                resources.getColor(R.color.log_error_entry_color, null)
            } else {
                resources.getColor(R.color.log_error_entry_color)
            }

            holder.itemView.tvText.setTextColor(color)

            holder.itemView.setOnLongClickListener {
                this@SslErrorsFragment.activity?.let {
                    (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?)?.let { clipboard ->
                        clipboard.setPrimaryClip(ClipData.newPlainText("", item.text))

                        Toast.makeText(
                                it,
                                R.string.main_activity_copied_to_clipboard,
                                Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_log, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Timber.d("onViewCreated")

        rvLog.layoutManager = LinearLayoutManager(activity)
        rvLog.setHasFixedSize(true)
        rvLog.adapter = accessLogAdapter

        lifecycleScope.launch {
            accessLogAdapter.entries = withContext(Dispatchers.IO) {
                Application.instance.sslErrorsDao.all().toMutableList()
            }

            accessLogAdapter.notifyDataSetChanged()

            // to avoid synchronization problems with accessLogAdapter.entries
            // as if we initialize the listener outside of the coroutine the variable could be modified
            // from this scope and from the listener
            listener = object : OnEntryAddedListener {
                override fun onNewEntry(entry: SslErrorEntry) {
                    // >= since we need space to add a new entry
                    while (accessLogAdapter.entries.size >= LIMIT) {
                        accessLogAdapter.entries.removeAt(accessLogAdapter.entries.size - 1)
                    }

                    accessLogAdapter.entries.add(0, entry)
                    accessLogAdapter.notifyDataSetChanged()
                }

                override fun clearLog() {
                    accessLogAdapter.entries.clear()
                    accessLogAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listener = null
    }

    private interface OnEntryAddedListener {
        fun onNewEntry(entry: SslErrorEntry)
        fun clearLog()
    }

    companion object {
        private const val LIMIT = 50

        private var listener: OnEntryAddedListener? = null

        fun addEntry(msg: String) {
            Timber.i(msg)

            val dateTime = SimpleDateFormat("hh:mm:ss", Locale.ENGLISH).format(
                    Date(System.currentTimeMillis()))

            val entry = SslErrorEntry("[$dateTime]: $msg")

            GlobalScope.launch(Dispatchers.IO) {
                val dao = Application.instance.sslErrorsDao
                val size = dao.size()

                // >= since we need some space to add a new entry
                if (size >= LIMIT) {
                    dao.deleteLastEntries(size - LIMIT + 1)
                }

                Application.instance.sslErrorsDao.add(entry)
            }

            GlobalScope.launch(Dispatchers.Main) {
                listener?.onNewEntry(entry)
            }
        }

        fun clearLog() {
            GlobalScope.launch(Dispatchers.IO) {
                Application.instance.sslErrorsDao.deleteAll()
            }

            GlobalScope.launch(Dispatchers.Main) {
                listener?.clearLog()
            }
        }
    }
}