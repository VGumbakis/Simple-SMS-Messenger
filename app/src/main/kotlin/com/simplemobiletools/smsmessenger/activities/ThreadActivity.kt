package com.simplemobiletools.smsmessenger.activities

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.text.TextUtils
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.RelativeLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.commons.helpers.isNougatPlus
import com.simplemobiletools.smsmessenger.R
import com.simplemobiletools.smsmessenger.adapters.AutoCompleteTextViewAdapter
import com.simplemobiletools.smsmessenger.adapters.ThreadAdapter
import com.simplemobiletools.smsmessenger.extensions.*
import com.simplemobiletools.smsmessenger.helpers.*
import com.simplemobiletools.smsmessenger.models.*
import kotlinx.android.synthetic.main.activity_thread.*
import kotlinx.android.synthetic.main.item_attachment.view.*
import kotlinx.android.synthetic.main.item_selected_contact.view.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class ThreadActivity : SimpleActivity() {
    private val MIN_DATE_TIME_DIFF_SECS = 300
    private val PICK_ATTACHMENT_INTENT = 1

    private var threadId = 0
    private var threadItems = ArrayList<ThreadItem>()
    private var bus: EventBus? = null
    private var participants = ArrayList<Contact>()
    private var messages = ArrayList<Message>()
    private var attachmentUris = LinkedHashSet<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_thread)

        val extras = intent.extras
        if (extras == null) {
            toast(R.string.unknown_error_occurred)
            finish()
            return
        }

        threadId = intent.getIntExtra(THREAD_ID, 0)
        intent.getStringExtra(THREAD_TITLE)?.let {
            supportActionBar?.title = it
        }

        bus = EventBus.getDefault()
        bus!!.register(this)

        ensureBackgroundThread {
            messages = getMessages(threadId)
            participants = if (messages.isEmpty()) {
                getThreadParticipants(threadId, null)
            } else {
                messages.first().participants
            }

            if (participants.isEmpty()) {
                val name = intent.getStringExtra(THREAD_TITLE) ?: ""
                val number = intent.getStringExtra(THREAD_NUMBER)
                if (number == null) {
                    toast(R.string.unknown_error_occurred)
                    finish()
                    return@ensureBackgroundThread
                }

                val contact = Contact(0, name, "", number)
                participants.add(contact)
            }

            messages.filter { it.attachment != null }.forEach {
                it.attachment!!.attachments.forEach {
                    try {
                        if (it.mimetype.startsWith("image/")) {
                            val fileOptions = BitmapFactory.Options()
                            fileOptions.inJustDecodeBounds = true
                            BitmapFactory.decodeStream(contentResolver.openInputStream(it.uri), null, fileOptions)
                            it.width = fileOptions.outWidth
                            it.height = fileOptions.outHeight
                        } else if (it.mimetype.startsWith("video/")) {
                            val metaRetriever = MediaMetadataRetriever()
                            metaRetriever.setDataSource(this, it.uri)
                            it.width = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
                            it.height = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
                        }

                        if (it.width < 0) {
                            it.width = 0
                        }

                        if (it.height < 0) {
                            it.height = 0
                        }
                    } catch (ignored: Exception) {
                    }
                }
            }

            setupAdapter()
            runOnUiThread {
                val threadTitle = participants.getThreadTitle()
                if (threadTitle.isNotEmpty()) {
                    supportActionBar?.title = participants.getThreadTitle()
                }

                if (messages.isEmpty()) {
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                    thread_type_message.requestFocus()
                }
            }
        }
        setupButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        bus?.unregister(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_thread, menu)
        menu.apply {
            findItem(R.id.delete).isVisible = threadItems.isNotEmpty()
            findItem(R.id.block_number).isVisible = isNougatPlus()
        }

        updateMenuItemColors(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (participants.isEmpty()) {
            return true
        }

        when (item.itemId) {
            R.id.block_number -> blockNumber()
            R.id.delete -> askConfirmDelete()
            R.id.manage_people -> managePeople()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == PICK_ATTACHMENT_INTENT && resultCode == Activity.RESULT_OK && resultData != null && resultData.data != null) {
            addAttachment(resultData.data!!)
        }
    }

    private fun setupAdapter() {
        threadItems = getThreadItems()
        invalidateOptionsMenu()

        runOnUiThread {
            val adapter = ThreadAdapter(this, threadItems, thread_messages_list, thread_messages_fastscroller) {}
            thread_messages_list.adapter = adapter
        }

        getAvailableContacts {
            runOnUiThread {
                val adapter = AutoCompleteTextViewAdapter(this, it)
                add_contact_or_number.setAdapter(adapter)
                add_contact_or_number.imeOptions = EditorInfo.IME_ACTION_NEXT
                add_contact_or_number.setOnItemClickListener { _, _, position, _ ->
                    val currContacts = (add_contact_or_number.adapter as AutoCompleteTextViewAdapter).resultList
                    val selectedContact = currContacts[position]
                    addSelectedContact(selectedContact)
                }

                add_contact_or_number.onTextChangeListener {
                    confirm_inserted_number.beVisibleIf(it.length > 2)
                }
            }
        }

        confirm_inserted_number.setOnClickListener {
            val number = add_contact_or_number.value
            val contact = Contact(number.hashCode(), number, "", number)
            addSelectedContact(contact)
        }
    }

    private fun setupButtons() {
        updateTextColors(thread_holder)
        thread_send_message.applyColorFilter(config.textColor)
        confirm_manage_contacts.applyColorFilter(config.textColor)
        thread_add_attachment.applyColorFilter(config.textColor)

        thread_send_message.setOnClickListener {
            sendMessage()
        }

        thread_send_message.isClickable = false
        thread_type_message.onTextChangeListener {
            checkSendMessageAvailability()
        }

        confirm_manage_contacts.setOnClickListener {
            hideKeyboard()
            thread_add_contacts.beGone()

            val numbers = participants.map { it.phoneNumber }.toSet()
            val newThreadId = getThreadId(numbers).toInt()
            if (threadId != newThreadId) {
                Intent(this, ThreadActivity::class.java).apply {
                    putExtra(THREAD_ID, newThreadId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(this)
                }
            }
        }

        thread_type_message.setText(intent.getStringExtra(THREAD_TEXT))
        thread_add_attachment.setOnClickListener {
            launchPickPhotoVideoIntent()
        }

        if (intent.extras?.containsKey(THREAD_ATTACHMENT_URI) == true) {
            val uri = Uri.parse(intent.getStringExtra(THREAD_ATTACHMENT_URI))
            addAttachment(uri)
        } else if (intent.extras?.containsKey(THREAD_ATTACHMENT_URIS) == true) {
            (intent.getSerializableExtra(THREAD_ATTACHMENT_URIS) as? ArrayList<Uri>)?.forEach {
                addAttachment(it)
            }
        }
    }

    private fun blockNumber() {
        val baseString = R.string.block_confirmation
        val numbers = participants.map { it.phoneNumber }.toTypedArray()
        val numbersString = TextUtils.join(", ", numbers)
        val question = String.format(resources.getString(baseString), numbersString)

        ConfirmationDialog(this, question) {
            ensureBackgroundThread {
                numbers.forEach {
                    addBlockedNumber(it)
                }
                refreshMessages()
                finish()
            }
        }
    }

    private fun askConfirmDelete() {
        ConfirmationDialog(this, getString(R.string.delete_whole_conversation_confirmation)) {
            deleteConversation(threadId)
            refreshMessages()
            finish()
        }
    }

    private fun managePeople() {
        if (thread_add_contacts.isVisible()) {
            hideKeyboard()
            thread_add_contacts.beGone()
        } else {
            showSelectedContacts()
            thread_add_contacts.beVisible()
            add_contact_or_number.requestFocus()
            showKeyboard(add_contact_or_number)
        }
    }

    private fun showSelectedContacts() {
        val views = ArrayList<View>()
        participants.forEach {
            val contact = it
            layoutInflater.inflate(R.layout.item_selected_contact, null).apply {
                selected_contact_name.text = contact.name
                selected_contact_remove.setOnClickListener {
                    if (contact.id != participants.first().id) {
                        removeSelectedContact(contact.id)
                    }
                }
                views.add(this)
            }
        }
        showSelectedContact(views)
    }

    private fun addSelectedContact(contact: Contact) {
        add_contact_or_number.setText("")
        if (participants.map { it.id }.contains(contact.id)) {
            return
        }

        participants.add(contact)
        showSelectedContacts()
    }

    private fun getThreadItems(): ArrayList<ThreadItem> {
        messages.sortBy { it.date }

        val items = ArrayList<ThreadItem>()
        var prevDateTime = 0
        var hadUnreadItems = false
        messages.forEach {
            // do not show the date/time above every message, only if the difference between the 2 messages is at least MIN_DATE_TIME_DIFF_SECS
            if (it.date - prevDateTime > MIN_DATE_TIME_DIFF_SECS) {
                items.add(ThreadDateTime(it.date))
                prevDateTime = it.date
            }
            items.add(it)

            if (it.type == Telephony.Sms.MESSAGE_TYPE_FAILED) {
                items.add(ThreadError(it.id))
            }

            if (!it.read) {
                hadUnreadItems = true
                markMessageRead(it.id, it.isMMS)
            }
        }

        if (hadUnreadItems) {
            bus?.post(Events.RefreshMessages())
        }

        return items
    }

    private fun launchPickPhotoVideoIntent() {
        val mimeTypes = arrayOf("image/*", "video/*")
        Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            startActivityForResult(this, PICK_ATTACHMENT_INTENT)
        }
    }

    private fun addAttachment(uri: Uri) {
        if (attachmentUris.contains(uri)) {
            return
        }

        attachmentUris.add(uri)
        thread_attachments_holder.beVisible()
        val attachmentView = layoutInflater.inflate(R.layout.item_attachment, null).apply {
            thread_attachments_wrapper.addView(this)
            thread_remove_attachment.setOnClickListener {
                thread_attachments_wrapper.removeView(this)
                attachmentUris.remove(uri)
                if (attachmentUris.isEmpty()) {
                    thread_attachments_holder.beGone()
                }
            }
        }

        val roundedCornersRadius = resources.getDimension(R.dimen.medium_margin).toInt()
        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .transform(CenterCrop(), RoundedCorners(roundedCornersRadius))

        Glide.with(this)
            .load(uri)
            .transition(DrawableTransitionOptions.withCrossFade())
            .apply(options)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                    attachmentView.thread_attachment_preview.beGone()
                    attachmentView.thread_remove_attachment.beGone()
                    showErrorToast(e?.localizedMessage ?: "")
                    return false
                }

                override fun onResourceReady(dr: Drawable?, a: Any?, t: Target<Drawable>?, d: DataSource?, i: Boolean): Boolean {
                    attachmentView.thread_attachment_preview.beVisible()
                    attachmentView.thread_remove_attachment.beVisible()
                    checkSendMessageAvailability()
                    return false
                }
            })
            .into(attachmentView.thread_attachment_preview)
    }

    private fun checkSendMessageAvailability() {
        if (thread_type_message.text.isNotEmpty() || attachmentUris.isNotEmpty()) {
            thread_send_message.isClickable = true
            thread_send_message.alpha = 0.9f
        } else {
            thread_send_message.isClickable = false
            thread_send_message.alpha = 0.4f
        }
    }

    private fun sendMessage() {
        val msg = thread_type_message.value
        if (msg.isEmpty() && attachmentUris.isEmpty()) {
            return
        }

        val numbers = participants.map { it.phoneNumber }.toTypedArray()
        val settings = Settings()
        settings.useSystemSending = true
        val transaction = Transaction(this, settings)
        val message = com.klinker.android.send_message.Message(msg, numbers)

        if (attachmentUris.isNotEmpty()) {
            for (uri in attachmentUris) {
                val byteArray = contentResolver.openInputStream(uri)?.readBytes() ?: continue
                val mimeType = contentResolver.getType(uri) ?: continue
                message.addMedia(byteArray, mimeType)
            }
        }

        try {
            transaction.sendNewMessage(message, threadId.toLong())

            thread_type_message.setText("")
            attachmentUris.clear()
            thread_attachments_holder.beGone()
            thread_attachments_wrapper.removeAllViews()
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    // show selected contacts, properly split to new lines when appropriate
    // based on https://stackoverflow.com/a/13505029/1967672
    private fun showSelectedContact(views: ArrayList<View>) {
        selected_contacts.removeAllViews()
        var newLinearLayout = LinearLayout(this)
        newLinearLayout.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        newLinearLayout.orientation = LinearLayout.HORIZONTAL

        val sideMargin = (selected_contacts.layoutParams as RelativeLayout.LayoutParams).leftMargin
        val mediumMargin = resources.getDimension(R.dimen.medium_margin).toInt()
        val parentWidth = realScreenSize.x - sideMargin * 2
        val firstRowWidth = parentWidth - resources.getDimension(R.dimen.normal_icon_size).toInt() + sideMargin / 2
        var widthSoFar = 0
        var isFirstRow = true

        for (i in views.indices) {
            val LL = LinearLayout(this)
            LL.orientation = LinearLayout.HORIZONTAL
            LL.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            LL.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            views[i].measure(0, 0)

            var params = LayoutParams(views[i].measuredWidth, LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, mediumMargin, 0)
            LL.addView(views[i], params)
            LL.measure(0, 0)
            widthSoFar += views[i].measuredWidth + mediumMargin

            val checkWidth = if (isFirstRow) firstRowWidth else parentWidth
            if (widthSoFar >= checkWidth) {
                isFirstRow = false
                selected_contacts.addView(newLinearLayout)
                newLinearLayout = LinearLayout(this)
                newLinearLayout.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                newLinearLayout.orientation = LinearLayout.HORIZONTAL
                params = LayoutParams(LL.measuredWidth, LL.measuredHeight)
                params.topMargin = mediumMargin
                newLinearLayout.addView(LL, params)
                widthSoFar = LL.measuredWidth
            } else {
                if (!isFirstRow) {
                    (LL.layoutParams as LayoutParams).topMargin = mediumMargin
                }
                newLinearLayout.addView(LL)
            }
        }
        selected_contacts.addView(newLinearLayout)
    }

    private fun removeSelectedContact(id: Int) {
        participants = participants.filter { it.id != id }.toMutableList() as ArrayList<Contact>
        showSelectedContacts()
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun refreshMessages(event: Events.RefreshMessages) {
        messages = getMessages(threadId)
        setupAdapter()
    }
}
