@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.tl2333.novelvoicereader.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.html.HtmlDecorationTemplates

/** Hosts Readium's EPUB fragment and never constructs it directly. */
class EpubReaderFragment : Fragment() {

    private val readerViewModel: ReaderViewModel
        get() = (requireActivity() as ReaderActivity).readerViewModel

    private var navigator: EpubNavigatorFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val session = (readerViewModel.loadState.value as? ReaderLoadState.Ready)?.session
            ?: error("EpubReaderFragment must only be attached after the publication is ready")

        // Readium requires its FragmentFactory to be installed before Fragment.onCreate so that
        // the child navigator can also be reconstructed safely after a configuration change.
        childFragmentManager.fragmentFactory = session.navigatorFactory.createFragmentFactory(
            // ReaderActivity intentionally discards FragmentManager state while the retained
            // ViewModel keeps the open Publication alive. Resume from the latest navigator
            // location on configuration recreation instead of jumping back to the launch locator.
            initialLocator = readerViewModel.currentLocator.value ?: session.initialLocator,
            initialPreferences = session.initialPreferences,
            configuration = EpubNavigatorFragment.Configuration {
                decorationTemplates = HtmlDecorationTemplates.defaultTemplates(
                    alpha = 0.32,
                    experimentalPositioning = true,
                )
            },
        )
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply {
        id = NAVIGATOR_CONTAINER_ID
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val restored = childFragmentManager.findFragmentByTag(NAVIGATOR_TAG)
        if (restored == null) {
            childFragmentManager.commitNow {
                setReorderingAllowed(true)
                add(
                    NAVIGATOR_CONTAINER_ID,
                    EpubNavigatorFragment::class.java,
                    null,
                    NAVIGATOR_TAG,
                )
            }
        }

        navigator = childFragmentManager.findFragmentByTag(NAVIGATOR_TAG)
            as? EpubNavigatorFragment
            ?: error("Readium's FragmentFactory did not create an EPUB navigator")
        readerViewModel.attachNavigator(requireNotNull(navigator))
    }

    override fun onDestroyView() {
        navigator?.let(readerViewModel::detachNavigator)
        navigator = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "epub-reader"

        // Stable across recreation; generated IDs would prevent FragmentManager restoration.
        private const val NAVIGATOR_CONTAINER_ID = 0x4e560201
        private const val NAVIGATOR_TAG = "readium-epub-navigator"
    }
}
