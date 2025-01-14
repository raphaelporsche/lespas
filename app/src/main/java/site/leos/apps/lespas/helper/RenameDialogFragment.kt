package site.leos.apps.lespas.helper

import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import site.leos.apps.lespas.R

class RenameDialogFragment: LesPasDialogFragment(R.layout.fragment_rename_dialog) {
    private lateinit var usedNames: ArrayList<String>
    private var requestType: Int = REQUEST_TYPE_ALBUM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usedNames = requireArguments().getStringArrayList(USED_NAMES) ?: arrayListOf()
        requestType = requireArguments().getInt(REQUEST_TYPE, REQUEST_TYPE_ALBUM)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.dialog_title_textview).text = when(requestType) {
            REQUEST_TYPE_ALBUM -> getString(R.string.rename_album)
            REQUEST_TYPE_PHOTO -> getString(R.string.rename_media)
            else -> ""
        }
        view.findViewById<TextInputEditText>(R.id.rename_textinputedittext).run {
            // Use append to move cursor to the end of text
            if (savedInstanceState == null) append(arguments?.getString(OLD_NAME))

            addTextChangedListener(FileNameValidator(this, usedNames))

            setOnEditorActionListener { _, actionId, keyEvent ->
                if (actionId == EditorInfo.IME_ACTION_GO || keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
                    error ?: run {
                        val name = this.text.toString().trim()    // Trim the leading and trailing blank
                        if (name.isNotEmpty()) {
                            if (requireArguments().getString(OLD_NAME)?.equals(name) != true) parentFragmentManager.setFragmentResult(RESULT_KEY_NEW_NAME, Bundle().apply {
                                putString(RESULT_KEY_NEW_NAME, name)
                                putInt(REQUEST_TYPE, requestType)
                            })
                            dismiss()
                        }
                    }
                }
                true
            }

            requestFocus()
        }

        requireDialog().window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    override fun onCancel(dialog: DialogInterface) {
        parentFragmentManager.setFragmentResult(RESULT_KEY_NEW_NAME, Bundle().apply {
            putString(RESULT_KEY_NEW_NAME, null)
            putInt(REQUEST_TYPE, requestType)
        })
        super.onCancel(dialog)
    }

    companion object {
        private const val OLD_NAME = "OLD_NAME"
        private const val USED_NAMES = "USED_NAMES"

        const val RESULT_KEY_NEW_NAME = "RESULT_KEY_NEW_NAME"
        const val REQUEST_TYPE = "REQUEST_TYPE"

        const val REQUEST_TYPE_ALBUM = 1
        const val REQUEST_TYPE_PHOTO = 2

        @JvmStatic
        fun newInstance(oldName: String, usedNames: List<String>, requestType: Int) = RenameDialogFragment().apply {
            arguments = Bundle().apply {
                putString(OLD_NAME, oldName)
                putStringArrayList(USED_NAMES, ArrayList(usedNames))
                putInt(REQUEST_TYPE, requestType)
            }
        }
    }
}
