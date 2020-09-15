/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.activities

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.stylish.StylishFragment
import com.kunzisoft.keepass.adapters.EntryAttachmentsItemsAdapter
import com.kunzisoft.keepass.database.element.Attachment
import com.kunzisoft.keepass.database.element.Database
import com.kunzisoft.keepass.database.element.DateInstant
import com.kunzisoft.keepass.database.element.Entry
import com.kunzisoft.keepass.database.element.icon.IconImage
import com.kunzisoft.keepass.icons.IconDrawableFactory
import com.kunzisoft.keepass.icons.assignDatabaseIcon
import com.kunzisoft.keepass.icons.assignDefaultDatabaseIcon
import com.kunzisoft.keepass.model.EntryAttachmentState
import com.kunzisoft.keepass.model.Field
import com.kunzisoft.keepass.model.StreamDirection
import com.kunzisoft.keepass.view.applyFontVisibility
import com.kunzisoft.keepass.view.collapse
import com.kunzisoft.keepass.view.expand
import org.joda.time.Duration
import org.joda.time.Instant

class EntryEditContentsFragment: StylishFragment() {

    private var fontInVisibility: Boolean = false

    private lateinit var entryTitleLayoutView: TextInputLayout
    private lateinit var entryTitleView: EditText
    private lateinit var entryIconView: ImageView
    private lateinit var entryUserNameView: EditText
    private lateinit var entryUrlView: EditText
    private lateinit var entryPasswordLayoutView: TextInputLayout
    private lateinit var entryPasswordView: EditText
    private lateinit var entryPasswordGeneratorView: View
    private lateinit var entryExpiresCheckBox: CompoundButton
    private lateinit var entryExpiresTextView: TextView
    private lateinit var entryNotesView: EditText
    private lateinit var extraFieldsContainerView: View
    private lateinit var extraFieldsListView: ViewGroup
    private lateinit var attachmentsContainerView: View
    private lateinit var attachmentsListView: RecyclerView

    private lateinit var attachmentsAdapter: EntryAttachmentsItemsAdapter

    private var iconColor: Int = 0
    private var expiresInstant: DateInstant = DateInstant(Instant.now().plus(Duration.standardDays(30)).toDate())

    var setOnDateClickListener: View.OnClickListener? = null
    var setOnPasswordGeneratorClickListener: View.OnClickListener? = null
    var setOnIconViewClickListener: View.OnClickListener? = null
    var setOnEditCustomField: ((Field) -> Unit)? = null
    var setOnRemoveAttachment: ((Attachment) -> Unit)? = null

    private var mDatabase: Database? = null
    private var mEntry: Entry? = null
    private var mIsNewEntry = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        val rootView = inflater.cloneInContext(contextThemed)
            .inflate(R.layout.fragment_entry_edit_contents, container, false)

        entryTitleLayoutView = rootView.findViewById(R.id.entry_edit_container_title)
        entryTitleView = rootView.findViewById(R.id.entry_edit_title)
        entryIconView = rootView.findViewById(R.id.entry_edit_icon_button)
        entryIconView.setOnClickListener {
            setOnIconViewClickListener?.onClick(it)
        }

        entryUserNameView = rootView.findViewById(R.id.entry_edit_user_name)
        entryUrlView = rootView.findViewById(R.id.entry_edit_url)
        entryPasswordLayoutView = rootView.findViewById(R.id.entry_edit_container_password)
        entryPasswordView = rootView.findViewById(R.id.entry_edit_password)
        entryPasswordGeneratorView = rootView.findViewById(R.id.entry_edit_password_generator_button)
        entryPasswordGeneratorView.setOnClickListener {
            setOnPasswordGeneratorClickListener?.onClick(it)
        }
        entryExpiresCheckBox = rootView.findViewById(R.id.entry_edit_expires_checkbox)
        entryExpiresTextView = rootView.findViewById(R.id.entry_edit_expires_text)
        entryExpiresTextView.setOnClickListener {
            if (entryExpiresCheckBox.isChecked)
                setOnDateClickListener?.onClick(it)
        }

        entryNotesView = rootView.findViewById(R.id.entry_edit_notes)

        extraFieldsContainerView = rootView.findViewById(R.id.extra_fields_container)
        extraFieldsListView = rootView.findViewById(R.id.extra_fields_list)

        attachmentsContainerView = rootView.findViewById(R.id.entry_attachments_container)
        attachmentsListView = rootView.findViewById(R.id.entry_attachments_list)
        attachmentsAdapter = EntryAttachmentsItemsAdapter(requireContext())
        attachmentsAdapter.onListSizeChangedListener = { previousSize, newSize ->
            if (previousSize > 0 && newSize == 0) {
                attachmentsContainerView.collapse(true)
            } else if (previousSize == 0 && newSize == 1) {
                attachmentsContainerView.expand(true)
            }
        }
        attachmentsListView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            adapter = attachmentsAdapter
            (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
        }

        entryExpiresCheckBox.setOnCheckedChangeListener { _, _ ->
            assignExpiresDateText()
        }

        // Retrieve the textColor to tint the icon
        val taIconColor = contextThemed?.theme?.obtainStyledAttributes(intArrayOf(android.R.attr.textColor))
        iconColor = taIconColor?.getColor(0, Color.WHITE) ?: Color.WHITE
        taIconColor?.recycle()

        mDatabase?.let { database ->
            mEntry?.let { entry ->
                populateViewsWithEntry(database, entry, mIsNewEntry)
            }
        }

        return rootView
    }

    fun setEntry(database: Database, entry: Entry, isNewEntry: Boolean) {
        mDatabase = database
        mEntry = entry
        mIsNewEntry = isNewEntry
    }

    private fun populateViewsWithEntry(database: Database, entry: Entry, isNewEntry: Boolean) {
        // Don't start the field reference manager, we want to see the raw ref
        database.stopManageEntry(entry)

        // Set info in view
        setIcon(database.drawFactory, entry.icon)
        title = entry.title
        username = if (isNewEntry && entry.username.isEmpty())
            database.defaultUsername
        else
            entry.username
        url = entry.url
        password = entry.password
        expires = entry.expires
        if (expires)
            expiresDate = entry.expiryTime
        notes = entry.notes
        assignExtraFields(entry.customFields.mapTo(ArrayList()) {
            Field(it.key, it.value)
        }) {
            setOnEditCustomField?.invoke(it)
        }
        assignAttachments(entry.getAttachments(database.binaryPool).toSet(), StreamDirection.UPLOAD) { attachment ->
            // Remove entry by clicking trash button
            entry.removeAttachment(attachment)
        }
    }

    fun populateEntryWithViews(database: Database, newEntry: Entry) {

        database.startManageEntry(newEntry)

        newEntry.apply {
            // Build info from view
            this@EntryEditContentsFragment.let { entryView ->
                removeAllFields()
                title = entryView.title
                username = entryView.username
                url = entryView.url
                password = entryView.password
                expires = entryView.expires
                if (entryView.expires) {
                    expiryTime = entryView.expiresDate
                }
                notes = entryView.notes
                entryView.getExtraFields().forEach { customField ->
                    putExtraField(customField.name, customField.protectedValue)
                }
                database.binaryPool.let { binaryPool ->
                    entryView.getAttachments().forEach {
                        putAttachment(it, binaryPool)
                    }
                }
            }
        }

        database.stopManageEntry(newEntry)
    }

    fun applyFontVisibilityToFields(fontInVisibility: Boolean) {
        this.fontInVisibility = fontInVisibility
    }

    var title: String
        get() {
            return entryTitleView.text.toString()
        }
        set(value) {
            entryTitleView.setText(value)
            if (fontInVisibility)
                entryTitleView.applyFontVisibility()
        }

    fun setDefaultIcon(iconFactory: IconDrawableFactory) {
        entryIconView.assignDefaultDatabaseIcon(iconFactory, iconColor)
    }

    fun setIcon(iconFactory: IconDrawableFactory, icon: IconImage) {
        entryIconView.assignDatabaseIcon(iconFactory, icon, iconColor)
    }

    var username: String
        get() {
            return entryUserNameView.text.toString()
        }
        set(value) {
            entryUserNameView.setText(value)
            if (fontInVisibility)
                entryUserNameView.applyFontVisibility()
        }

    var url: String
        get() {
            return entryUrlView.text.toString()
        }
        set(value) {
            entryUrlView.setText(value)
            if (fontInVisibility)
                entryUrlView.applyFontVisibility()
        }

    var password: String
        get() {
            return entryPasswordView.text.toString()
        }
        set(value) {
            entryPasswordView.setText(value)
            if (fontInVisibility) {
                entryPasswordView.applyFontVisibility()
            }
        }

    private fun assignExpiresDateText() {
        entryExpiresTextView.text = if (entryExpiresCheckBox.isChecked) {
            entryExpiresTextView.setOnClickListener(setOnDateClickListener)
            expiresInstant.getDateTimeString(resources)
        } else {
            entryExpiresTextView.setOnClickListener(null)
            resources.getString(R.string.never)
        }
        if (fontInVisibility)
            entryExpiresTextView.applyFontVisibility()
    }

    var expires: Boolean
        get() {
            return entryExpiresCheckBox.isChecked
        }
        set(value) {
            entryExpiresCheckBox.isChecked = value
            assignExpiresDateText()
        }

    var expiresDate: DateInstant
        get() {
            return expiresInstant
        }
        set(value) {
            expiresInstant = value
            assignExpiresDateText()
        }

    var notes: String
        get() {
            return entryNotesView.text.toString()
        }
        set(value) {
            entryNotesView.setText(value)
            if (fontInVisibility)
                entryNotesView.applyFontVisibility()
        }

    /* -------------
     * Extra Fields
     * -------------
     */

    private var mExtraFieldsList: MutableList<Field> = ArrayList()
    private var mOnEditButtonClickListener: ((item: Field)->Unit)? = null

    private fun buildViewFromField(extraField: Field): View? {
        val inflater = context?.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater?
        val itemView: View? = inflater?.inflate(R.layout.item_entry_edit_extra_field, extraFieldsListView, false)
        itemView?.id = View.NO_ID

        val extraFieldValueContainer: TextInputLayout? = itemView?.findViewById(R.id.entry_extra_field_value_container)
        extraFieldValueContainer?.isPasswordVisibilityToggleEnabled = extraField.protectedValue.isProtected
        extraFieldValueContainer?.hint = extraField.name
        extraFieldValueContainer?.id = View.NO_ID

        val extraFieldValue: TextInputEditText? = itemView?.findViewById(R.id.entry_extra_field_value)
        extraFieldValue?.apply {
            if (extraField.protectedValue.isProtected) {
                inputType = extraFieldValue.inputType or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
            }
            setText(extraField.protectedValue.toString())
            if (fontInVisibility)
                applyFontVisibility()
        }
        extraFieldValue?.id = View.NO_ID
        extraFieldValue?.tag = "FIELD_VALUE_TAG"

        val extraFieldEditButton: View? = itemView?.findViewById(R.id.entry_extra_field_edit)
        extraFieldEditButton?.setOnClickListener {
            mOnEditButtonClickListener?.invoke(extraField)
        }
        extraFieldEditButton?.id = View.NO_ID

        return itemView
    }

    fun getExtraFields(): List<Field> {
        for (index in 0 until extraFieldsListView.childCount) {
            val extraFieldValue: EditText = extraFieldsListView.getChildAt(index)
                    .findViewWithTag("FIELD_VALUE_TAG")
            mExtraFieldsList[index].protectedValue.stringValue = extraFieldValue.text?.toString() ?: ""
        }
        return mExtraFieldsList
    }

    /**
     * Remove all children and add new views for each field
     */
    fun assignExtraFields(fields: List<Field>,
                          onEditButtonClickListener: ((item: Field)->Unit)?) {
        extraFieldsContainerView.visibility = if (fields.isEmpty()) View.GONE else View.VISIBLE
        // Reinit focused field
        mExtraFieldsList.clear()
        mExtraFieldsList.addAll(fields)
        extraFieldsListView.removeAllViews()
        fields.forEach {
            extraFieldsListView.addView(buildViewFromField(it))
        }
        mOnEditButtonClickListener = onEditButtonClickListener

        /*
        if (mExtraFieldsList.size > 0) {
            extraFieldsContainerView.expand(false)
        } else {
            extraFieldsContainerView.collapse(false)
        }
         */
    }

    /**
     * Update an extra field or create a new one if doesn't exists
     */
    fun putExtraField(extraField: Field) {
        extraFieldsContainerView.visibility = View.VISIBLE
        val oldField = mExtraFieldsList.firstOrNull { it.name == extraField.name }
        oldField?.let {
            val index = mExtraFieldsList.indexOf(oldField)
            mExtraFieldsList.removeAt(index)
            mExtraFieldsList.add(index, extraField)
            extraFieldsListView.removeViewAt(index)
            val newView = buildViewFromField(extraField)
            extraFieldsListView.addView(newView, index)
        } ?: kotlin.run {
            mExtraFieldsList.add(extraField)
            val newView = buildViewFromField(extraField)
            extraFieldsListView.addView(newView)
        }
    }

    fun replaceExtraField(oldExtraField: Field, newExtraField: Field) {
        extraFieldsContainerView.visibility = View.VISIBLE
        val index = mExtraFieldsList.indexOf(oldExtraField)
        mExtraFieldsList.removeAt(index)
        mExtraFieldsList.add(index, newExtraField)
        extraFieldsListView.removeViewAt(index)
        extraFieldsListView.addView(buildViewFromField(newExtraField), index)
    }

    fun removeExtraField(oldExtraField: Field) {
        val previousSize = mExtraFieldsList.size
        val index = mExtraFieldsList.indexOf(oldExtraField)
        mExtraFieldsList.removeAt(index)
        extraFieldsListView.removeViewAt(index)
        val newSize = mExtraFieldsList.size

        if (previousSize > 0 && newSize == 0) {
            extraFieldsContainerView.collapse(true)
        } else if (previousSize == 0 && newSize == 1) {
            extraFieldsContainerView.expand(true)
        }
    }

    fun getExtraFieldViewPosition(field: Field, position: (Float) -> Unit) {
        extraFieldsListView.post {
            val index = mExtraFieldsList.indexOf(field)
            val child = extraFieldsListView.getChildAt(index)
            child.requestFocus()
            position.invoke(extraFieldsListView.y
                    + (child?.y
                    ?: 0F)
            )
        }
    }

    /* -------------
     * Attachments
     * -------------
     */

    fun getAttachments(): List<Attachment> {
        return attachmentsAdapter.itemsList.map { it.attachment }
    }

    fun assignAttachments(attachments: Set<Attachment>,
                          streamDirection: StreamDirection,
                          onDeleteItem: (attachment: Attachment)->Unit) {
        attachmentsContainerView.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        attachmentsAdapter.assignItems(attachments.map { EntryAttachmentState(it, streamDirection) })
        attachmentsAdapter.onDeleteButtonClickListener = { item ->
            onDeleteItem.invoke(item.attachment)
        }
    }

    fun containsAttachment(): Boolean {
        return !attachmentsAdapter.isEmpty()
    }

    fun containsAttachment(attachment: EntryAttachmentState): Boolean {
        return attachmentsAdapter.contains(attachment)
    }

    fun putAttachment(attachment: EntryAttachmentState) {
        attachmentsContainerView.visibility = View.VISIBLE
        attachmentsAdapter.putItem(attachment)
    }

    fun removeAttachment(attachment: EntryAttachmentState) {
        attachmentsAdapter.removeItem(attachment)
    }

    fun clearAttachments() {
        attachmentsAdapter.clear()
    }

    fun getAttachmentViewPosition(attachment: EntryAttachmentState, position: (Float) -> Unit) {
        attachmentsListView.postDelayed({
            position.invoke(attachmentsContainerView.y
                    + attachmentsListView.y
                    + (attachmentsListView.getChildAt(attachmentsAdapter.indexOf(attachment))?.y
                    ?: 0F)
            )
        }, 250)
    }

    /**
     * Validate or not the entry form
     *
     * @return ErrorValidation An error with a message or a validation without message
     */
    fun isValid(): Boolean {
        // TODO
        return true
    }

}