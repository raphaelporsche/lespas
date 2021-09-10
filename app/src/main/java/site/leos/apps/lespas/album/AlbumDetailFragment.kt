package site.leos.apps.lespas.album

import android.content.*
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.app.SharedElementCallback
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.asLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.*
import androidx.recyclerview.selection.SelectionTracker.Builder
import androidx.recyclerview.widget.*
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.work.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.photo.BottomControlsFragment
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoSlideFragment
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.ShareReceiverActivity
import java.io.File
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

class AlbumDetailFragment : Fragment(), ActionMode.Callback {
    private lateinit var album: Album
    private var scrollTo = ""

    private var actionMode: ActionMode? = null

    private lateinit var stub: View
    private lateinit var dateIndicator: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var mAdapter: PhotoGridAdapter

    private lateinit var selectionTracker: SelectionTracker<String>
    private lateinit var sharedSelection: MutableSet<String>
    private lateinit var lastSelection: MutableSet<String>

    private val albumModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by activityViewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val currentPhotoModel: PhotoSlideFragment.CurrentPhotoViewModel by activityViewModels()

    private lateinit var sharedPhoto: Photo
    private lateinit var snapseedCatcher: BroadcastReceiver
    private lateinit var snapseedOutputObserver: ContentObserver
    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver

    private val publishModel: NCShareViewModel by activityViewModels()
    private lateinit var sharedByMe: NCShareViewModel.ShareByMe

    private var sortOrderChanged = false

    private lateinit var addFileLauncher: ActivityResultLauncher<String>

    private var isSnapseedEnabled = false
    private var snapseedEditAction: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)

        album = arguments?.getParcelable(KEY_ALBUM)!!
        sharedByMe = NCShareViewModel.ShareByMe(album.id, album.name, arrayListOf())

        // Must be restore here
        lastSelection = mutableSetOf()
        sharedSelection = mutableSetOf()
        savedInstanceState?.let {
            lastSelection = it.getStringArray(SELECTION)?.toMutableSet() ?: mutableSetOf()
            sharedSelection = it.getStringArray(SHARED_SELECTION)?.toMutableSet() ?: mutableSetOf()
            sortOrderChanged = it.getBoolean(SORT_ORDER_CHANGED)
        } ?: run { arguments?.getString(KEY_SCROLL_TO)?.apply { scrollTo = this }}

        mAdapter = PhotoGridAdapter(
            { view, position ->
                currentPhotoModel.run {
                    setCurrentPhoto(mAdapter.getPhotoAt(position), position)
                    setLastPosition(position)
                }

                // Get a stub as fake toolbar since the toolbar belongs to MainActivity and it will disappear during fragment transaction
                stub.background = (activity as MainActivity).getToolbarViewContent()

                reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                exitTransition = MaterialElevationScale(false).apply {
                    duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                    excludeTarget(R.id.stub, true)
                    excludeTarget(view, true)
                }

                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(view, view.transitionName)
                    .replace(R.id.container_root, PhotoSlideFragment.newInstance(album), PhotoSlideFragment::class.java.canonicalName)
                    .add(R.id.container_bottom_toolbar, BottomControlsFragment.newInstance(album), BottomControlsFragment::class.java.canonicalName)
                    .addToBackStack(null)
                    .commit()
            },
            { photo, view, type -> imageLoaderModel.loadPhoto(photo, view, type) { startPostponedEnterTransition() } }
        ) { visible -> (activity as? AppCompatActivity)?.supportActionBar?.setDisplayShowTitleEnabled(visible) }.apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
        }

        // Adjusting the shared element mapping
        setExitSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                recyclerView.findViewHolderForAdapterPosition(currentPhotoModel.getCurrentPosition())?.let {
                   sharedElements?.put(names?.get(0)!!, it.itemView.findViewById(R.id.photo))
                }
            }
        })

        // Broadcast receiver listening on share destination
        snapseedCatcher = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent!!.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)?.packageName!!.substringAfterLast('.') == "snapseed") {
                    // Register content observer if integration with snapseed setting is on
                    if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.snapseed_pref_key), false)) {
                        context!!.contentResolver.apply {
                            unregisterContentObserver(snapseedOutputObserver)
                            registerContentObserver(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                true,
                                snapseedOutputObserver
                            )
                        }
                    }
                }
            }
        }
        context?.registerReceiver(snapseedCatcher, IntentFilter(CHOOSER_SPY_ACTION))

        // Content observer looking for Snapseed output
        snapseedOutputObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            private val workerName = "${AlbumDetailFragment::class.java.canonicalName}.SNAPSEED_WORKER"
            private var lastId = ""
            private lateinit var snapseedWork: OneTimeWorkRequest

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)

                // ContentObserver got called twice, once for itself, once for it's descendant, all with same last path segment
                if (uri?.lastPathSegment!! != lastId) {
                    lastId = uri.lastPathSegment!!

                    snapseedWork = OneTimeWorkRequestBuilder<SnapseedResultWorker>().setInputData(
                        // TODO publish status is not persistent locally
                        //workDataOf(SnapseedResultWorker.KEY_IMAGE_URI to uri.toString(), SnapseedResultWorker.KEY_SHARED_PHOTO to sharedPhoto.id, SnapseedResultWorker.KEY_ALBUM to album.id, SnapseedResultWorker.KEY_PUBLISHED to publishModel.isShared(album.id))).build()
                        workDataOf(SnapseedResultWorker.KEY_IMAGE_URI to uri.toString(), SnapseedResultWorker.KEY_SHARED_PHOTO to sharedPhoto.id, SnapseedResultWorker.KEY_ALBUM to album.id)).build()
                    with(WorkManager.getInstance(requireContext())) {
                        enqueueUniqueWork(workerName, ExistingWorkPolicy.KEEP, snapseedWork)

                        getWorkInfosForUniqueWorkLiveData(workerName).observe(parentFragmentManager.findFragmentById(R.id.container_root)!!, { workInfo->
                            if (workInfo != null) {
                                // If replace original is on, remove old bitmaps from cache and take care of cover too
                                if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(requireContext().getString(R.string.snapseed_replace_pref_key), false)) {
                                    imageLoaderModel.invalid(sharedPhoto.id)
                                    // Update cover if needed, cover id can be found only in adapter
                                    mAdapter.updateCover(sharedPhoto)
                                }
                            }
                        })
                    }

                    requireContext().contentResolver.unregisterContentObserver(this)
                }
            }
        }

        removeOriginalBroadcastReceiver = RemoveOriginalBroadcastReceiver {
            if (it) {
                val photos = mutableListOf<Photo>()
                for (photoId in sharedSelection) mAdapter.getPhotoBy(photoId).run { if (id != album.cover) photos.add(this) }
                // TODO publish status is not persistent locally
                //if (photos.isNotEmpty()) actionModel.deletePhotos(photos, album.name, publishModel.isShared(album.id))
                if (photos.isNotEmpty()) actionModel.deletePhotos(photos, album.name)
            }
            sharedSelection.clear()
        }

        addFileLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) ?: run {
                    AcquiringDialogFragment.newInstance(uris as ArrayList<Uri>, album,false).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_albumdetail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        stub = view.findViewById(R.id.stub)
        dateIndicator = view.findViewById(R.id.date_indicator)
        recyclerView = view.findViewById(R.id.photogrid)

        postponeEnterTransition()
        ViewCompat.setTransitionName(recyclerView, album.id)

        with(recyclerView) {
            // Special span size to show cover at the top of the grid
            val defaultSpanCount = (layoutManager as GridLayoutManager).spanCount
            layoutManager = GridLayoutManager(context, defaultSpanCount).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int { return if (position == 0) defaultSpanCount else 1 }
                }
            }

            adapter = mAdapter

            selectionTracker = Builder(
                "photoSelection",
                this,
                PhotoGridAdapter.PhotoKeyProvider(mAdapter),
                PhotoGridAdapter.PhotoDetailsLookup(this),
                StorageStrategy.createStringStorage()
            ).withSelectionPredicate(object : SelectionTracker.SelectionPredicate<String>() {
                override fun canSetStateForKey(key: String, nextState: Boolean): Boolean = (key.isNotEmpty())
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = (position != 0)
                override fun canSelectMultiple(): Boolean = true
            }).build().apply {
                addObserver(object : SelectionTracker.SelectionObserver<String>() {
                    override fun onSelectionChanged() {
                        super.onSelectionChanged()

                        val selectionSize = selectionTracker.selection.size()

                        snapseedEditAction?.isEnabled = selectionSize == 1 && isSnapseedEnabled && !Tools.isMediaPlayable(mAdapter.getPhotoBy(selectionTracker.selection.first()).mimeType)

                        if (selectionTracker.hasSelection() && actionMode == null) {
                            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this@AlbumDetailFragment)
                            actionMode?.let { it.title = getString(R.string.selected_count, selectionSize) }
                        } else if (!(selectionTracker.hasSelection()) && actionMode != null) {
                            actionMode?.finish()
                            actionMode = null
                        } else actionMode?.title = getString(R.string.selected_count, selectionSize)
                    }

                    override fun onItemStateChanged(key: String, selected: Boolean) {
                        super.onItemStateChanged(key, selected)
                        if (selected) lastSelection.add(key)
                        else lastSelection.remove(key)
                    }
                })
            }
            mAdapter.setSelectionTracker(selectionTracker)

            // Get scroll position after scroll idle
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private val hideHandler = Handler(Looper.getMainLooper())
                private val hideDateIndicator = Runnable {
                    TransitionManager.beginDelayedTransition(recyclerView.parent as ViewGroup, Fade().apply { duration = 500 })
                    dateIndicator.visibility = View.GONE
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    // Hints the date (or 1st character of the name if sorting order is by name) of last photo shown in the list
                    ((layoutManager as GridLayoutManager)).run {
                        if ((findLastCompletelyVisibleItemPosition() < mAdapter.itemCount - 1) || (findFirstCompletelyVisibleItemPosition() > 0)) {
                            hideHandler.removeCallbacksAndMessages(null)
                            dateIndicator.apply {
                                text = if (album.sortOrder == Album.BY_NAME_ASC || album.sortOrder == Album.BY_NAME_DESC)
                                    mAdapter.getPhotoAt((layoutManager as GridLayoutManager).findLastVisibleItemPosition()).name.take(1)
                                else
                                    mAdapter.getPhotoAt((layoutManager as GridLayoutManager).findLastVisibleItemPosition()).dateTaken.format(DateTimeFormatter.ISO_LOCAL_DATE)
                                visibility = View.VISIBLE
                            }
                        }
                    }

                    when(newState) {
                        RecyclerView.SCROLL_STATE_IDLE-> {
                            // Hide the date indicator after showing it for 1 minute
                            if (dateIndicator.visibility == View.VISIBLE) hideHandler.postDelayed(hideDateIndicator, 1000)
                        }
                    }
                }
            })
        }

        LocalBroadcastManager.getInstance(requireContext().applicationContext).registerReceiver(removeOriginalBroadcastReceiver, IntentFilter(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL))

        albumModel.getAlbumDetail(album.id).observe(viewLifecycleOwner, {
            // Cover might changed, photo might be deleted, so get updates from latest here
            this.album = it.album

            mAdapter.setAlbum(it)
            (activity as? AppCompatActivity)?.supportActionBar?.title = it.album.name

            // Scroll to reveal the new position, e.g. the position where PhotoSliderFragment left
            if (currentPhotoModel.getCurrentPosition() != currentPhotoModel.getLastPosition()) {
                (recyclerView.layoutManager as GridLayoutManager).scrollToPosition(currentPhotoModel.getCurrentPosition())
                currentPhotoModel.setLastPosition(currentPhotoModel.getCurrentPosition())
            }

            // Scroll to designated photo at first run
            if (scrollTo.isNotEmpty()) {
                (recyclerView.layoutManager as GridLayoutManager).scrollToPosition(with(mAdapter.getPhotoPosition(scrollTo)) { if (this >=0) this else 0})
                scrollTo = ""
            }

            // Restore selection state
            if (lastSelection.isNotEmpty()) lastSelection.forEach {selected-> selectionTracker.select(selected) }
        })

        publishModel.shareByMe.asLiveData().observe(viewLifecycleOwner) { shares ->
            sharedByMe = shares.find { it.fileId == album.id } ?: NCShareViewModel.ShareByMe(album.id, album.name, arrayListOf())
            mAdapter.setRecipient(sharedByMe)
        }

        // Rename result handler
        parentFragmentManager.setFragmentResultListener(AlbumRenameDialogFragment.RESULT_KEY_NEW_NAME, viewLifecycleOwner) { key, bundle->
            if (key == AlbumRenameDialogFragment.RESULT_KEY_NEW_NAME) {
                bundle.getString(AlbumRenameDialogFragment.RESULT_KEY_NEW_NAME)?.let { newName->
                    if (newName != album.name) {
                        CoroutineScope(Dispatchers.Main).launch(Dispatchers.IO) {
                            if (albumModel.isAlbumExisted(newName)) {
                                withContext(Dispatchers.Main) {
                                    if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.name_existed, newName), getString(android.R.string.ok), false).show(parentFragmentManager, CONFIRM_DIALOG)
                                }
                            } else {
                                with(sharedByMe.with.isNotEmpty()) {
                                    actionModel.renameAlbum(album.id, album.name, newName, this)

                                    // TODO What if sharedByMe is not available when working offline
                                    if (this) publishModel.renameShare(sharedByMe, newName)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { key, bundle ->
            if (key == ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY) {
                if (bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY) == DELETE_REQUEST_KEY) {
                    if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                        val photos = mutableListOf<Photo>()
                        for (photoId in selectionTracker.selection) mAdapter.getPhotoBy(photoId).run { if (id != album.cover) photos.add(this) }
                        // TODO publish status is not persistent locally
                        //if (photos.isNotEmpty()) actionModel.deletePhotos(photos, album.name, publishModel.isShared(album.id))
                        if (photos.isNotEmpty()) actionModel.deletePhotos(photos, album.name)
                    }
                    selectionTracker.clearSelection()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(true)

        isSnapseedEnabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.snapseed_pref_key), false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(SELECTION, lastSelection.toTypedArray())
        outState.putStringArray(SHARED_SELECTION, sharedSelection.toTypedArray())
        outState.putBoolean(SORT_ORDER_CHANGED, sortOrderChanged)
    }

    override fun onDestroyView() {
        recyclerView.clearOnScrollListeners()
        recyclerView.adapter = null

        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)

        super.onDestroyView()
    }

    override fun onDestroy() {
        // Time to update album meta file if sort order changed in this session, if cover is not uploaded yet, meta will be maintained in SyncAdapter when cover fileId is available
        if (sortOrderChanged && !album.cover.contains('.')) actionModel.updateMeta(album)

        requireContext().apply {
            unregisterReceiver(snapseedCatcher)
            contentResolver.unregisterContentObserver(snapseedOutputObserver)
        }

        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.album_detail_menu, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.option_menu_sortbydateasc).isChecked = false
        menu.findItem(R.id.option_menu_sortbydatedesc).isChecked = false
        menu.findItem(R.id.option_menu_sortbynameasc).isChecked = false
        menu.findItem(R.id.option_menu_sortbynamedesc).isChecked = false

        when(album.sortOrder) {
            Album.BY_DATE_TAKEN_ASC-> menu.findItem(R.id.option_menu_sortbydateasc).isChecked = true
            Album.BY_DATE_TAKEN_DESC-> menu.findItem(R.id.option_menu_sortbydatedesc).isChecked = true
            Album.BY_NAME_ASC-> menu.findItem(R.id.option_menu_sortbynameasc).isChecked = true
            Album.BY_NAME_DESC-> menu.findItem(R.id.option_menu_sortbynamedesc).isChecked = true
        }

        // Disable publish function when this is a newly created album which does not exist on server yet
        if (album.eTag.isEmpty()) menu.findItem(R.id.option_menu_publish).isEnabled = false

        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.option_menu_add_photo-> {
                addFileLauncher.launch("*/*")
                true
            }
            R.id.option_menu_rename-> {
                if (parentFragmentManager.findFragmentByTag(RENAME_DIALOG) == null) AlbumRenameDialogFragment.newInstance(album.name).show(parentFragmentManager, RENAME_DIALOG)
                true
            }
            R.id.option_menu_settings-> {
                exitTransition = null
                reenterTransition = null
                parentFragmentManager.beginTransaction().replace(R.id.container_root, SettingsFragment()).addToBackStack(null).commit()
                true
            }
            R.id.option_menu_sortbydateasc-> {
                updateSortOrder(Album.BY_DATE_TAKEN_ASC)
                true
            }
            R.id.option_menu_sortbydatedesc-> {
                updateSortOrder(Album.BY_DATE_TAKEN_DESC)
                true
            }
            R.id.option_menu_sortbynameasc-> {
                updateSortOrder(Album.BY_NAME_ASC)
                true
            }
            R.id.option_menu_sortbynamedesc-> {
                updateSortOrder(Album.BY_NAME_DESC)
                true
            }
            R.id.option_menu_publish-> {
                // Get meaningful label for each recipient
                publishModel.sharees.value.let { sharees->
                    sharedByMe.with.forEach { recipient-> sharees.find { it.name == recipient.sharee.name && it.type == recipient.sharee.type}?.let { recipient.sharee.label = it.label }}
                }
                if (parentFragmentManager.findFragmentByTag(PUBLISH_DIALOG) == null) AlbumPublishDialogFragment.newInstance(sharedByMe).show(parentFragmentManager, PUBLISH_DIALOG)
                true
            }
            else-> false
        }
    }

    // On special Actions of this fragment
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu): Boolean {
        mode?.menuInflater?.inflate(R.menu.actions_mode, menu)

        snapseedEditAction = menu.findItem(R.id.snapseed_edit)

        // Disable snapseed edit action menu if Snapseed is not installed
        isSnapseedEnabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.snapseed_pref_key), false)
        snapseedEditAction?.isEnabled = isSnapseedEnabled

        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.remove -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete), true, DELETE_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                true
            }
            R.id.share -> {
                try {
                    val uris = arrayListOf<Uri>()
                    val appRootFolder = Tools.getLocalRoot(requireContext())
                    val cachePath = requireActivity().cacheDir
                    val authority = getString(R.string.file_authority)

                    sharedSelection.clear()
                    for (photoId in selectionTracker.selection) {
                        sharedSelection.add(photoId)
                        //with(mAdapter.getPhotoAt(i.toInt())) {
                        with(mAdapter.getPhotoBy(photoId)) {
                            // Synced file is named after id, not yet synced file is named after file's name
                            File(appRootFolder, if (eTag.isNotEmpty()) id else name).copyTo(File(cachePath, name), true, 4096)
                            uris.add(FileProvider.getUriForFile(requireContext(), authority, File(cachePath, name)))
                        }
                    }

                    val clipData = ClipData.newUri(requireActivity().contentResolver, "", uris[0])
                    for (i in 1 until uris.size)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clipData.addItem(requireActivity().contentResolver, ClipData.Item(uris[i]))
                        else clipData.addItem(ClipData.Item(uris[i]))

                    //sharedPhoto = mAdapter.getPhotoAt(selectionTracker.selection.first().toInt())
                    sharedPhoto = mAdapter.getPhotoBy(selectionTracker.selection.first())
                    if (selectionTracker.selection.size() > 1) {
                        startActivity(
                            Intent.createChooser(
                                Intent().apply {
                                    action = Intent.ACTION_SEND_MULTIPLE
                                    type = sharedPhoto.mimeType
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                    this.clipData = clipData
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, true)
                                }, null
                            )
                        )
                    } else {
                        // If sharing only one picture, use ACTION_SEND instead, so that other apps which won't accept ACTION_SEND_MULTIPLE will work
                        startActivity(Intent.createChooser(Intent().apply {
                            action = Intent.ACTION_SEND
                            type = sharedPhoto.mimeType
                            putExtra(Intent.EXTRA_STREAM, uris[0])
                            this.clipData = clipData
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, true)
                        }, null))
                    }

                    selectionTracker.clearSelection()
                } catch (e: Exception) { e.printStackTrace() }

                true
            }
            R.id.select_all -> {
                for (i in 1 until mAdapter.itemCount) selectionTracker.select(mAdapter.getPhotoId(i))
                true
            }
            R.id.snapseed_edit-> {
                try {
                    val uris = arrayListOf<Uri>()
                    val appRootFolder = Tools.getLocalRoot(requireContext())
                    val cachePath = requireActivity().cacheDir
                    val authority = getString(R.string.file_authority)

                    sharedSelection.clear()
                    for (photoId in selectionTracker.selection) {
                        sharedSelection.add(photoId)
                        //with(mAdapter.getPhotoAt(i.toInt())) {
                        with(mAdapter.getPhotoBy(photoId)) {
                            // Synced file is named after id, not yet synced file is named after file's name
                            File(appRootFolder, if (eTag.isNotEmpty()) id else name).copyTo(File(cachePath, name), true, 4096)
                            uris.add(FileProvider.getUriForFile(requireContext(), authority, File(cachePath, name)))
                        }
                    }

                    sharedPhoto = mAdapter.getPhotoBy(selectionTracker.selection.first())
                    startActivity(Intent().apply {
                        action = Intent.ACTION_SEND
                        data = uris[0]
                        putExtra(Intent.EXTRA_STREAM, uris[0])
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        setClassName(SettingsFragment.SNAPSEED_PACKAGE_NAME, SettingsFragment.SNAPSEED_MAIN_ACTIVITY_CLASS_NAME)
                    })
                } catch (e: Exception) { e.printStackTrace() }

                // Send broadcast just like system share does when user chooses Snapseed, so that we can catch editing result
                requireContext().sendBroadcast(Intent().apply {
                    action = CHOOSER_SPY_ACTION
                    putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName(SettingsFragment.SNAPSEED_PACKAGE_NAME, SettingsFragment.SNAPSEED_MAIN_ACTIVITY_CLASS_NAME))
                })

                selectionTracker.clearSelection()
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionTracker.clearSelection()
        actionMode = null
    }

    private fun updateSortOrder(newOrder: Int) {
        albumModel.setSortOrder(album.id, newOrder)
        sortOrderChanged = true
    }

    // Adapter for photo grid
    class PhotoGridAdapter(private val clickListener: (View, Int) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit, private val titleUpdater: (Boolean) -> Unit
    ) : ListAdapter<Photo, RecyclerView.ViewHolder>(PhotoDiffCallback()) {
        private lateinit var selectionTracker: SelectionTracker<String>
        private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })
        private var currentHolder = 0
        //private var oldSortOrder = Album.BY_DATE_TAKEN_ASC
        private var recipients = mutableListOf<NCShareViewModel.Recipient>()
        private var recipientText = ""

        inner class CoverViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bindViewItem() {
                with(itemView) {
                    currentList.firstOrNull()?.let { cover->
                        imageLoader(cover, findViewById(R.id.cover), ImageLoaderViewModel.TYPE_COVER)
                        findViewById<TextView>(R.id.title).text = cover.name

                        val days = Duration.between(
                            cover.dateTaken.atZone(ZoneId.systemDefault()).toInstant(),
                            cover.lastModified.atZone(ZoneId.systemDefault()).toInstant()
                        ).toDays().toInt()
                        findViewById<TextView>(R.id.duration).text = when (days) {
                            in 0..21 -> resources.getString(R.string.duration_days, days + 1)
                            in 22..56 -> resources.getString(R.string.duration_weeks, days / 7)
                            in 57..365 -> resources.getString(R.string.duration_months, days / 30)
                            else -> resources.getString(R.string.duration_years, days / 365)
                        }

                        findViewById<TextView>(R.id.total).text = resources.getString(R.string.total_photo, currentList.size - 1)

                        if (recipients.size > 0) {
                            var names = recipients[0].sharee.label
                            for (i in 1 until recipients.size) names += ", ${recipients[i].sharee.label}"
                            findViewById<TextView>(R.id.recipients).apply {
                                text = String.format(recipientText, names)
                                visibility = View.VISIBLE
                            }
                        } else findViewById<TextView>(R.id.recipients).visibility = View.GONE
                    }
                }
            }
        }

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bindViewItem(photo: Photo, isActivated: Boolean) {
                itemView.let {
                    it.isActivated = isActivated

                    with(it.findViewById<ImageView>(R.id.photo)) {
                        imageLoader(photo, this, ImageLoaderViewModel.TYPE_GRID)

                        if (this.isActivated) {
                            colorFilter = selectedFilter
                            it.findViewById<ImageView>(R.id.selection_mark).visibility = View.VISIBLE
                        } else {
                            clearColorFilter()
                            it.findViewById<ImageView>(R.id.selection_mark).visibility = View.GONE
                        }

                        ViewCompat.setTransitionName(this, photo.id)

                        setOnClickListener { if (!selectionTracker.hasSelection()) clickListener(this, bindingAdapterPosition) }

                        it.findViewById<ImageView>(R.id.play_mark).visibility = if (Tools.isMediaPlayable(photo.mimeType) && !this.isActivated) View.VISIBLE else View.GONE
                    }
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getPhotoId(bindingAdapterPosition)
            }
        }

        override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_COVER else TYPE_PHOTO

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            recipientText = parent.context.getString(R.string.published_to)
            return if (viewType == TYPE_COVER) CoverViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cover, parent, false))
            else PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo, parent, false))

        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is PhotoViewHolder) holder.bindViewItem(currentList[position], selectionTracker.isSelected(currentList[position].id))
            else (holder as CoverViewHolder).bindViewItem()
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            super.onViewAttachedToWindow(holder)
            if (holder is CoverViewHolder) {
                currentHolder = System.identityHashCode(holder)
                titleUpdater(false)
            }
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            super.onViewDetachedFromWindow(holder)
            if (holder is CoverViewHolder && System.identityHashCode(holder) == currentHolder) titleUpdater(true)
        }

        internal fun setAlbum(album: AlbumWithPhotos) {
            val photos = mutableListOf<Photo>()
            photos.addAll(
                when(album.album.sortOrder) {
                    Album.BY_DATE_TAKEN_ASC-> album.photos.sortedWith(compareBy { it.dateTaken })
                    Album.BY_DATE_TAKEN_DESC-> album.photos.sortedWith(compareByDescending { it.dateTaken })
                    Album.BY_DATE_MODIFIED_ASC-> album.photos.sortedWith(compareBy { it.lastModified })
                    Album.BY_DATE_MODIFIED_DESC-> album.photos.sortedWith(compareByDescending { it.lastModified })
                    Album.BY_NAME_ASC-> album.photos.sortedWith(compareBy { it.name })
                    Album.BY_NAME_DESC-> album.photos.sortedWith(compareByDescending { it.name })
                    else-> album.photos
                }
            )
            album.album.run { photos.add(0, Photo(cover, id, name, "", startDate, endDate, coverWidth, coverHeight, photos.size.toString(), coverBaseline)) }
            submitList(photos)
        }

        //internal fun getRecipient(): List<NCShareViewModel.Recipient> = recipients
        internal fun setRecipient(share: NCShareViewModel.ShareByMe) {
            this.recipients = share.with
            notifyItemChanged(0)
        }

        internal fun getPhotoAt(position: Int): Photo = currentList[position]
        internal fun getPhotoBy(photoId: String): Photo = currentList.last { it.id == photoId }
        internal fun updateCover(sharedPhoto: Photo) {
            //notifyItemChanged(currentList.indexOfLast { it.id == sharedPhoto.id })
            if (sharedPhoto.id == currentList[0].id) notifyItemChanged(0)
        }

        internal fun setSelectionTracker(selectionTracker: SelectionTracker<String>) { this.selectionTracker = selectionTracker }
        internal fun getPhotoId(position: Int): String = currentList[position].id
        internal fun getPhotoPosition(photoId: String): Int = currentList.indexOfLast { it.id == photoId }
        class PhotoKeyProvider(private val adapter: PhotoGridAdapter): ItemKeyProvider<String>(SCOPE_CACHED) {
            override fun getKey(position: Int): String = adapter.getPhotoId(position)
            override fun getPosition(key: String): Int = with(adapter.getPhotoPosition(key)) { if (this >= 0) this else RecyclerView.NO_POSITION }
        }
        class PhotoDetailsLookup(private val recyclerView: RecyclerView): ItemDetailsLookup<String>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<String>? {
                recyclerView.findChildViewUnder(e.x, e.y)?.let {
                    val holder = recyclerView.getChildViewHolder(it)
                    return if (holder is PhotoViewHolder) holder.getItemDetails() else null
                }
                return null
            }
        }

        companion object {
            private const val TYPE_COVER = 0
            private const val TYPE_PHOTO = 1
        }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<Photo>() {
        override fun areItemsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.lastModified == newItem.lastModified && oldItem.name == newItem.name && oldItem.shareId == newItem.shareId && oldItem.mimeType == newItem.mimeType
    }

    companion object {
        private const val RENAME_DIALOG = "RENAME_DIALOG"
        private const val PUBLISH_DIALOG = "PUBLISH_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val SELECTION = "SELECTION"
        private const val SHARED_SELECTION = "SHARED_SELECTION"
        private const val SORT_ORDER_CHANGED = "SORT_ORDER_CHANGED"

        private const val DELETE_REQUEST_KEY = "ALBUMDETAIL_DELETE_REQUEST_KEY"

        private const val TAG_ACQUIRING_DIALOG = "ALBUM_DETAIL_ACQUIRING_DIALOG"

        const val CHOOSER_SPY_ACTION = "site.leos.apps.lespas.CHOOSER_ALBUMDETAIL"

        const val KEY_ALBUM = "ALBUM"
        const val KEY_SCROLL_TO = "KEY_SCROLL_TO"   // SearchResultFragment use this for scrolling to designed photo

        @JvmStatic
        fun newInstance(album: Album, photoId: String) = AlbumDetailFragment().apply {
            arguments = Bundle().apply {
                putParcelable(KEY_ALBUM, album)
                putString(KEY_SCROLL_TO, photoId)
            }
        }
    }
}