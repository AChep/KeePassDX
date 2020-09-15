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
 */
package com.kunzisoft.keepass.activities

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import com.google.android.material.snackbar.Snackbar
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.dialogs.*
import com.kunzisoft.keepass.activities.dialogs.FileTooBigDialogFragment.Companion.MAX_WARNING_BINARY_FILE
import com.kunzisoft.keepass.activities.helpers.SelectFileHelper
import com.kunzisoft.keepass.activities.lock.LockingActivity
import com.kunzisoft.keepass.database.element.*
import com.kunzisoft.keepass.database.element.icon.IconImage
import com.kunzisoft.keepass.database.element.icon.IconImageStandard
import com.kunzisoft.keepass.database.element.node.NodeId
import com.kunzisoft.keepass.education.EntryEditActivityEducation
import com.kunzisoft.keepass.model.*
import com.kunzisoft.keepass.notifications.AttachmentFileNotificationService
import com.kunzisoft.keepass.notifications.ClipboardEntryNotificationService
import com.kunzisoft.keepass.notifications.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_CREATE_ENTRY_TASK
import com.kunzisoft.keepass.notifications.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_ENTRY_TASK
import com.kunzisoft.keepass.notifications.KeyboardEntryNotificationService
import com.kunzisoft.keepass.otp.OtpElement
import com.kunzisoft.keepass.otp.OtpEntryFields
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.tasks.AttachmentFileBinderManager
import com.kunzisoft.keepass.timeout.TimeoutHelper
import com.kunzisoft.keepass.utils.MenuUtil
import com.kunzisoft.keepass.utils.UriUtil
import com.kunzisoft.keepass.view.asError
import com.kunzisoft.keepass.view.showActionError
import com.kunzisoft.keepass.view.updateLockPaddingLeft
import org.joda.time.DateTime
import java.util.*
import kotlin.collections.ArrayList

class EntryEditActivity : LockingActivity(),
        IconPickerDialogFragment.IconPickerListener,
        EntryCustomFieldDialogFragment.EntryCustomFieldListener,
        GeneratePasswordDialogFragment.GeneratePasswordListener,
        SetOTPDialogFragment.CreateOtpListener,
        DatePickerDialog.OnDateSetListener,
        TimePickerDialog.OnTimeSetListener,
        FileTooBigDialogFragment.ActionChooseListener,
        ReplaceFileDialogFragment.ActionChooseListener {

    private var mDatabase: Database? = null

    // Refs of an entry and group in database, are not modifiable
    private var mEntry: Entry? = null
    private var mParent: Group? = null
    // New or copy of mEntry in the database to be modifiable
    private var mNewEntry: Entry? = null
    private var mIsNew: Boolean = false

    // Views
    private var coordinatorLayout: CoordinatorLayout? = null
    private var scrollView: NestedScrollView? = null
    private var entryEditContentsFragment: EntryEditContentsFragment? = null
    private var entryEditAddToolBar: Toolbar? = null
    private var validateButton: View? = null
    private var lockView: View? = null

    // To manage attachments
    private var mSelectFileHelper: SelectFileHelper? = null
    private var mAttachmentFileBinderManager: AttachmentFileBinderManager? = null
    private var mAllowMultipleAttachments: Boolean = false
    private var mTempAttachments = ArrayList<Attachment>()

    // Education
    private var entryEditActivityEducation: EntryEditActivityEducation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_entry_edit)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_white_24dp)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        coordinatorLayout = findViewById(R.id.entry_edit_coordinator_layout)

        scrollView = findViewById(R.id.entry_edit_scroll)
        scrollView?.scrollBarStyle = View.SCROLLBARS_INSIDE_INSET

        lockView = findViewById(R.id.lock_button)
        lockView?.setOnClickListener {
            lockAndExit()
        }

        // Focus view to reinitialize timeout
        resetAppTimeoutWhenViewFocusedOrChanged(coordinatorLayout)

        stopService(Intent(this, ClipboardEntryNotificationService::class.java))
        stopService(Intent(this, KeyboardEntryNotificationService::class.java))

        // Likely the app has been killed exit the activity
        mDatabase = Database.getInstance()

        // Entry is retrieve, it's an entry to update
        intent.getParcelableExtra<NodeId<UUID>>(KEY_ENTRY)?.let {
            mIsNew = false
            // Create an Entry copy to modify from the database entry
            mEntry = mDatabase?.getEntryById(it)

            // Retrieve the parent
            mEntry?.let { entry ->
                mParent = entry.parent
                // If no parent, add root group as parent
                if (mParent == null) {
                    mParent = mDatabase?.rootGroup
                    entry.parent = mParent
                }
            }

            // Create the new entry from the current one
            if (savedInstanceState?.containsKey(KEY_NEW_ENTRY) != true) {
                mEntry?.let { entry ->
                    // Create a copy to modify
                    mNewEntry = Entry(entry).also { newEntry ->
                        // WARNING Remove the parent to keep memory with parcelable
                        newEntry.removeParent()
                    }
                }
            }
        }

        // Parent is retrieve, it's a new entry to create
        intent.getParcelableExtra<NodeId<*>>(KEY_PARENT)?.let {
            mIsNew = true
            // Create an empty new entry
            if (savedInstanceState?.containsKey(KEY_NEW_ENTRY) != true) {
                mNewEntry = mDatabase?.createEntry()
            }
            mParent = mDatabase?.getGroupById(it)
            // Add the default icon from parent if not a folder
            val parentIcon = mParent?.icon
            if (parentIcon != null
                    && parentIcon.iconId != IconImage.UNKNOWN_ID
                    && parentIcon.iconId != IconImageStandard.FOLDER) {
                temporarilySaveAndShowSelectedIcon(parentIcon)
            } else {
                mDatabase?.drawFactory?.let { iconFactory ->
                    entryEditContentsFragment?.setDefaultIcon(iconFactory)
                }
            }
        }

        // Retrieve the new entry after an orientation change
        if (savedInstanceState?.containsKey(KEY_NEW_ENTRY) == true) {
            mNewEntry = savedInstanceState.getParcelable(KEY_NEW_ENTRY)
        }

        if (savedInstanceState?.containsKey(TEMP_ATTACHMENTS) == true) {
            mTempAttachments = savedInstanceState.getParcelableArrayList(TEMP_ATTACHMENTS) ?: mTempAttachments
        }

        // Close the activity if entry or parent can't be retrieve
        if (mNewEntry == null || mParent == null) {
            finish()
            return
        }

        entryEditContentsFragment = supportFragmentManager.findFragmentByTag("entry_edit_contents") as? EntryEditContentsFragment?
        if (entryEditContentsFragment == null) {
            entryEditContentsFragment = EntryEditContentsFragment()
            supportFragmentManager.beginTransaction()
                    .replace(R.id.entry_edit_contents, entryEditContentsFragment!!, "entry_edit_contents")
                    .commit()
        }
        entryEditContentsFragment?.apply {
            mDatabase?.let { database ->
                mNewEntry?.let { newEntry ->
                    entryEditContentsFragment?.setEntry(database, newEntry, mIsNew)
                }
            }
            applyFontVisibilityToFields(PreferencesUtil.fieldFontIsInVisibility(this@EntryEditActivity))
            setOnDateClickListener = View.OnClickListener {
                expiresDate.date.let { expiresDate ->
                    val dateTime = DateTime(expiresDate)
                    val defaultYear = dateTime.year
                    val defaultMonth = dateTime.monthOfYear-1
                    val defaultDay = dateTime.dayOfMonth
                    DatePickerFragment.getInstance(defaultYear, defaultMonth, defaultDay)
                            .show(supportFragmentManager, "DatePickerFragment")
                }
            }
            setOnPasswordGeneratorClickListener = View.OnClickListener {
                openPasswordGenerator()
            }
            // Add listener to the icon
            setOnIconViewClickListener = View.OnClickListener {
                IconPickerDialogFragment.launch(this@EntryEditActivity)
            }
            setOnRemoveAttachment = { attachment ->
                mAttachmentFileBinderManager?.removeBinaryAttachment(attachment)
            }
            setOnEditCustomField = { field ->
                editCustomField(field)
            }
        }

        // Assign title
        title = if (mIsNew) getString(R.string.add_entry) else getString(R.string.edit_entry)

        // Bottom Bar
        entryEditAddToolBar = findViewById(R.id.entry_edit_bottom_bar)
        entryEditAddToolBar?.apply {
            menuInflater.inflate(R.menu.entry_edit, menu)

            menu.findItem(R.id.menu_add_field).apply {
                val allowCustomField = mNewEntry?.allowCustomFields() == true
                isEnabled = allowCustomField
                isVisible = allowCustomField
            }

            // Attachment not compatible below KitKat
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                menu.findItem(R.id.menu_add_attachment).isVisible = false
            }

            menu.findItem(R.id.menu_add_otp).apply {
                val allowOTP = mDatabase?.allowOTP == true
                isEnabled = allowOTP
                // OTP not compatible below KitKat
                isVisible = allowOTP && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
            }

            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_add_field -> {
                        addNewCustomField()
                        true
                    }
                    R.id.menu_add_attachment -> {
                        addNewAttachment(item)
                        true
                    }
                    R.id.menu_add_otp -> {
                        setupOTP()
                        true
                    }
                    else -> true
                }
            }
        }

        // To retrieve attachment
        mSelectFileHelper = SelectFileHelper(this)
        mAttachmentFileBinderManager = AttachmentFileBinderManager(this)

        // Save button
        validateButton = findViewById(R.id.entry_edit_validate)
        validateButton?.setOnClickListener { saveEntry() }

        // Verify the education views
        entryEditActivityEducation = EntryEditActivityEducation(this)

        // Create progress dialog
        mProgressDatabaseTaskProvider?.onActionFinish = { actionTask, result ->
            when (actionTask) {
                ACTION_DATABASE_CREATE_ENTRY_TASK,
                ACTION_DATABASE_UPDATE_ENTRY_TASK -> {
                    if (result.isSuccess)
                        finish()
                }
            }
            coordinatorLayout?.showActionError(result)
        }
    }

    override fun onResume() {
        super.onResume()

        lockView?.visibility = if (PreferencesUtil.showLockDatabaseButton(this)) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // Padding if lock button visible
        entryEditAddToolBar?.updateLockPaddingLeft()

        mAllowMultipleAttachments = mDatabase?.allowMultipleAttachments == true
        mAttachmentFileBinderManager?.apply {
            registerProgressTask()
            onActionTaskListener = object : AttachmentFileNotificationService.ActionTaskListener {
                override fun onAttachmentAction(fileUri: Uri, entryAttachmentState: EntryAttachmentState) {
                    when (entryAttachmentState.downloadState) {
                        AttachmentState.START -> {
                            entryEditContentsFragment?.apply {
                                // When only one attachment is allowed
                                if (!mAllowMultipleAttachments) {
                                    clearAttachments()
                                }
                                putAttachment(entryAttachmentState)
                                // Scroll to the attachment position
                                getAttachmentViewPosition(entryAttachmentState) {
                                    scrollView?.smoothScrollTo(0, it.toInt())
                                }
                            }
                        }
                        AttachmentState.IN_PROGRESS -> {
                            entryEditContentsFragment?.putAttachment(entryAttachmentState)
                        }
                        AttachmentState.COMPLETE -> {
                            entryEditContentsFragment?.apply {
                                putAttachment(entryAttachmentState)
                                // Scroll to the attachment position
                                getAttachmentViewPosition(entryAttachmentState) {
                                    scrollView?.smoothScrollTo(0, it.toInt())
                                }
                            }
                        }
                        AttachmentState.ERROR -> {
                            entryEditContentsFragment?.removeAttachment(entryAttachmentState)
                            coordinatorLayout?.let {
                                Snackbar.make(it, R.string.error_file_not_create, Snackbar.LENGTH_LONG).asError().show()
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    override fun onPause() {
        mAttachmentFileBinderManager?.unregisterProgressTask()

        super.onPause()
    }

    private fun temporarilySaveAndShowSelectedIcon(icon: IconImage) {
        mNewEntry?.icon = icon
        mDatabase?.drawFactory?.let { iconDrawFactory ->
            entryEditContentsFragment?.setIcon(iconDrawFactory, icon)
        }
    }

    /**
     * Open the password generator fragment
     */
    private fun openPasswordGenerator() {
        GeneratePasswordDialogFragment().show(supportFragmentManager, "PasswordGeneratorFragment")
    }

    /**
     * Add a new customized field
     */
    private fun addNewCustomField() {
        EntryCustomFieldDialogFragment.getInstance().show(supportFragmentManager, "customFieldDialog")
    }

    private fun editCustomField(field: Field) {
        EntryCustomFieldDialogFragment.getInstance(field).show(supportFragmentManager, "customFieldDialog")
    }

    override fun onNewCustomFieldApproved(newField: Field) {
        entryEditContentsFragment?.apply {
            putExtraField(newField)
            getExtraFieldViewPosition(newField) { position ->
                // TODO scrollView?.smoothScrollTo(0, position.toInt())
            }
        }
    }

    override fun onEditCustomFieldApproved(oldField: Field, newField: Field) {
        entryEditContentsFragment?.replaceExtraField(oldField, newField)
    }

    override fun onDeleteCustomFieldApproved(oldField: Field) {
        entryEditContentsFragment?.removeExtraField(oldField)
    }

    /**
     * Add a new attachment
     */
    private fun addNewAttachment(item: MenuItem) {
        mSelectFileHelper?.selectFileOnClickViewListener?.onMenuItemClick(item)
    }

    override fun onValidateUploadFileTooBig(attachmentToUploadUri: Uri?, fileName: String?) {
        if (attachmentToUploadUri != null && fileName != null) {
            buildNewAttachment(attachmentToUploadUri, fileName)
        }
    }

    override fun onValidateReplaceFile(attachmentToUploadUri: Uri?, attachment: Attachment?) {
        startUploadAttachment(attachmentToUploadUri, attachment)
    }

    private fun startUploadAttachment(attachmentToUploadUri: Uri?, attachment: Attachment?) {
        if (attachmentToUploadUri != null && attachment != null) {
            // Start uploading in service
            mAttachmentFileBinderManager?.startUploadAttachment(attachmentToUploadUri, attachment)
            // Add in temp list
            mTempAttachments.add(attachment)
        }
    }

    private fun buildNewAttachment(attachmentToUploadUri: Uri, fileName: String) {
        val compression = mDatabase?.compressionForNewEntry() ?: false
        mDatabase?.buildNewBinary(applicationContext.filesDir, false, compression)?.let { binaryAttachment ->
            val entryAttachment = Attachment(fileName, binaryAttachment)
            // Ask to replace the current attachment
            if ((mDatabase?.allowMultipleAttachments != true && entryEditContentsFragment?.containsAttachment() == true) ||
                    entryEditContentsFragment?.containsAttachment(EntryAttachmentState(entryAttachment, StreamDirection.UPLOAD)) == true) {
                ReplaceFileDialogFragment.build(attachmentToUploadUri, entryAttachment)
                        .show(supportFragmentManager, "replacementFileFragment")
            } else {
                startUploadAttachment(attachmentToUploadUri, entryAttachment)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        mSelectFileHelper?.onActivityResultCallback(requestCode, resultCode, data) { uri ->
            uri?.let { attachmentToUploadUri ->
                // TODO Async to get the name
                UriUtil.getFileData(this, attachmentToUploadUri)?.also { documentFile ->
                    documentFile.name?.let { fileName ->
                        if (documentFile.length() > MAX_WARNING_BINARY_FILE) {
                            FileTooBigDialogFragment.build(attachmentToUploadUri, fileName)
                                    .show(supportFragmentManager, "fileTooBigFragment")
                        } else {
                            buildNewAttachment(attachmentToUploadUri, fileName)
                        }
                    }
                }
            }
        }
    }

    /**
     * Set up OTP (HOTP or TOTP) and add it as extra field
     */
    private fun setupOTP() {
        // Retrieve the current otpElement if exists
        // and open the dialog to set up the OTP
        SetOTPDialogFragment.build(mEntry?.getOtpElement()?.otpModel)
                .show(supportFragmentManager, "addOTPDialog")
    }

    /**
     * Saves the new entry or update an existing entry in the database
     */
    private fun saveEntry() {
        // Launch a validation and show the error if present
        if (entryEditContentsFragment?.isValid() == true) {
            // Clone the entry
            mNewEntry?.let { newEntry ->

                // WARNING Add the parent previously deleted
                newEntry.parent = mEntry?.parent
                // Build info
                newEntry.lastAccessTime = DateInstant()
                newEntry.lastModificationTime = DateInstant()

                mDatabase?.let { database ->
                    entryEditContentsFragment?.populateEntryWithViews(database, newEntry)
                }

                // Delete temp attachment if not used
                mTempAttachments.forEach {
                    mDatabase?.binaryPool?.let { binaryPool ->
                        if (!newEntry.getAttachments(binaryPool).contains(it)) {
                            mDatabase?.removeAttachmentIfNotUsed(it)
                        }
                    }
                }

                // Open a progress dialog and save entry
                if (mIsNew) {
                    mParent?.let { parent ->
                        mProgressDatabaseTaskProvider?.startDatabaseCreateEntry(
                                newEntry,
                                parent,
                                !mReadOnly && mAutoSaveEnable
                        )
                    }
                } else {
                    mEntry?.let { oldEntry ->
                        mProgressDatabaseTaskProvider?.startDatabaseUpdateEntry(
                                oldEntry,
                                newEntry,
                                !mReadOnly && mAutoSaveEnable
                        )
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)

        val inflater = menuInflater
        inflater.inflate(R.menu.database, menu)
        // Save database not needed here
        menu.findItem(R.id.menu_save_database)?.isVisible = false
        MenuUtil.contributionMenuInflater(inflater, menu)

        entryEditActivityEducation?.let {
            Handler().post { performedNextEducation(it) }
        }

        return true
    }

    private fun performedNextEducation(entryEditActivityEducation: EntryEditActivityEducation) {
        val passwordGeneratorView: View? = null //TODO entryEditContentsFragment?.entryPasswordGeneratorView
        val generatePasswordEducationPerformed = passwordGeneratorView != null
                && entryEditActivityEducation.checkAndPerformedGeneratePasswordEducation(
                passwordGeneratorView,
                {
                    openPasswordGenerator()
                },
                {
                    performedNextEducation(entryEditActivityEducation)
                }
        )
        if (!generatePasswordEducationPerformed) {
            val addNewFieldView: View? = entryEditAddToolBar?.findViewById(R.id.menu_add_field)
            val addNewFieldEducationPerformed = mNewEntry != null
                    && mNewEntry!!.allowCustomFields() && addNewFieldView != null
                    && addNewFieldView.visibility == View.VISIBLE
                    && entryEditActivityEducation.checkAndPerformedEntryNewFieldEducation(
                    addNewFieldView,
                    {
                        addNewCustomField()
                    },
                    {
                        performedNextEducation(entryEditActivityEducation)
                    }
            )
            if (!addNewFieldEducationPerformed) {
                val attachmentView: View? = entryEditAddToolBar?.findViewById(R.id.menu_add_attachment)
                val addAttachmentEducationPerformed = attachmentView != null && attachmentView.visibility == View.VISIBLE
                        && entryEditActivityEducation.checkAndPerformedAttachmentEducation(
                        attachmentView,
                        {
                            mSelectFileHelper?.selectFileOnClickViewListener?.onClick(attachmentView)
                        },
                        {
                            performedNextEducation(entryEditActivityEducation)
                        }
                )
                if (!addAttachmentEducationPerformed) {
                    val setupOtpView: View? = entryEditAddToolBar?.findViewById(R.id.menu_add_otp)
                    setupOtpView != null && setupOtpView.visibility == View.VISIBLE
                            && entryEditActivityEducation.checkAndPerformedSetUpOTPEducation(
                            setupOtpView,
                            {
                                setupOTP()
                            }
                    )
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save_database -> {
                mProgressDatabaseTaskProvider?.startDatabaseSave(!mReadOnly)
            }
            R.id.menu_contribute -> {
                MenuUtil.onContributionItemSelected(this)
                return true
            }
            android.R.id.home -> {
                onBackPressed()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onOtpCreated(otpElement: OtpElement) {
        // Update the otp field with otpauth:// url
        val otpField = OtpEntryFields.buildOtpField(otpElement,
                mEntry?.title, mEntry?.username)
        mEntry?.putExtraField(otpField.name, otpField.protectedValue)
        entryEditContentsFragment?.apply {
            putExtraField(otpField)
            getExtraFieldViewPosition(otpField) { position ->
                // TODO scrollView?.smoothScrollTo(0, position.toInt())
            }
        }
    }

    override fun iconPicked(bundle: Bundle) {
        IconPickerDialogFragment.getIconStandardFromBundle(bundle)?.let { icon ->
            temporarilySaveAndShowSelectedIcon(icon)
        }
    }

    override fun onDateSet(datePicker: DatePicker?, year: Int, month: Int, day: Int) {
        // To fix android 4.4 issue
        // https://stackoverflow.com/questions/12436073/datepicker-ondatechangedlistener-called-twice
        if (datePicker?.isShown == true) {
            entryEditContentsFragment?.expiresDate?.date?.let { expiresDate ->
                // Save the date
                entryEditContentsFragment?.expiresDate =
                        DateInstant(DateTime(expiresDate)
                                .withYear(year)
                                .withMonthOfYear(month + 1)
                                .withDayOfMonth(day)
                                .toDate())
                // Launch the time picker
                val dateTime = DateTime(expiresDate)
                val defaultHour = dateTime.hourOfDay
                val defaultMinute = dateTime.minuteOfHour
                TimePickerFragment.getInstance(defaultHour, defaultMinute)
                        .show(supportFragmentManager, "TimePickerFragment")
            }
        }
    }

    override fun onTimeSet(timePicker: TimePicker?, hours: Int, minutes: Int) {
        entryEditContentsFragment?.expiresDate?.date?.let { expiresDate ->
            // Save the date
            entryEditContentsFragment?.expiresDate =
                    DateInstant(DateTime(expiresDate)
                            .withHourOfDay(hours)
                            .withMinuteOfHour(minutes)
                            .toDate())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mNewEntry?.let { newEntry ->
            mDatabase?.let { database ->
                entryEditContentsFragment?.populateEntryWithViews(database, newEntry)
            }
            outState.putParcelable(KEY_NEW_ENTRY, newEntry)
        }

        outState.putParcelableArrayList(TEMP_ATTACHMENTS, mTempAttachments)

        super.onSaveInstanceState(outState)
    }

    override fun acceptPassword(bundle: Bundle) {
        bundle.getString(GeneratePasswordDialogFragment.KEY_PASSWORD_ID)?.let {
            entryEditContentsFragment?.password = it
        }

        entryEditActivityEducation?.let {
            Handler().post { performedNextEducation(it) }
        }
    }

    override fun cancelPassword(bundle: Bundle) {
        // Do nothing here
    }

    override fun onBackPressed() {
        AlertDialog.Builder(this)
                .setMessage(R.string.discard_changes)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.discard) { _, _ ->
                    super@EntryEditActivity.onBackPressed()
                }.create().show()
    }

    override fun finish() {
        // Assign entry callback as a result in all case
        try {
            mNewEntry?.let {
                val bundle = Bundle()
                val intentEntry = Intent()
                bundle.putParcelable(ADD_OR_UPDATE_ENTRY_KEY, mNewEntry)
                intentEntry.putExtras(bundle)
                if (mIsNew) {
                    setResult(ADD_ENTRY_RESULT_CODE, intentEntry)
                } else {
                    setResult(UPDATE_ENTRY_RESULT_CODE, intentEntry)
                }
            }
            super.finish()
        } catch (e: Exception) {
            // Exception when parcelable can't be done
            Log.e(TAG, "Cant add entry as result", e)
        }
    }

    companion object {

        private val TAG = EntryEditActivity::class.java.name

        // Keys for current Activity
        const val KEY_ENTRY = "entry"
        const val KEY_PARENT = "parent"

        // SaveInstanceState
        const val KEY_NEW_ENTRY = "new_entry"
        const val TEMP_ATTACHMENTS = "TEMP_ATTACHMENTS"

        // Keys for callback
        const val ADD_ENTRY_RESULT_CODE = 31
        const val UPDATE_ENTRY_RESULT_CODE = 32
        const val ADD_OR_UPDATE_ENTRY_REQUEST_CODE = 7129
        const val ADD_OR_UPDATE_ENTRY_KEY = "ADD_OR_UPDATE_ENTRY_KEY"

        /**
         * Launch EntryEditActivity to update an existing entry
         *
         * @param activity from activity
         * @param pwEntry Entry to update
         */
        fun launch(activity: Activity, pwEntry: Entry) {
            if (TimeoutHelper.checkTimeAndLockIfTimeout(activity)) {
                val intent = Intent(activity, EntryEditActivity::class.java)
                intent.putExtra(KEY_ENTRY, pwEntry.nodeId)
                activity.startActivityForResult(intent, ADD_OR_UPDATE_ENTRY_REQUEST_CODE)
            }
        }

        /**
         * Launch EntryEditActivity to add a new entry
         *
         * @param activity from activity
         * @param pwGroup Group who will contains new entry
         */
        fun launch(activity: Activity, pwGroup: Group) {
            if (TimeoutHelper.checkTimeAndLockIfTimeout(activity)) {
                val intent = Intent(activity, EntryEditActivity::class.java)
                intent.putExtra(KEY_PARENT, pwGroup.nodeId)
                activity.startActivityForResult(intent, ADD_OR_UPDATE_ENTRY_REQUEST_CODE)
            }
        }
    }
}
