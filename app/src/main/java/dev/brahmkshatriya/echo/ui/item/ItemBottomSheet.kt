package dev.brahmkshatriya.echo.ui.item

import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.ArtistFollowClient
import dev.brahmkshatriya.echo.common.clients.LibraryClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.databinding.DialogMediaItemBinding
import dev.brahmkshatriya.echo.databinding.ItemDialogButtonBinding
import dev.brahmkshatriya.echo.databinding.ItemDialogButtonLoadingBinding
import dev.brahmkshatriya.echo.ui.common.openFragment
import dev.brahmkshatriya.echo.ui.exception.ExceptionFragment.Companion.copyToClipboard
import dev.brahmkshatriya.echo.ui.media.MediaContainerViewHolder.Media.Companion.bind
import dev.brahmkshatriya.echo.utils.autoCleared
import dev.brahmkshatriya.echo.utils.loadInto
import dev.brahmkshatriya.echo.utils.observe
import dev.brahmkshatriya.echo.viewmodels.PlayerViewModel
import dev.brahmkshatriya.echo.viewmodels.SnackBar.Companion.createSnack

@AndroidEntryPoint
class ItemBottomSheet : BottomSheetDialogFragment() {
    companion object {
        fun newInstance(
            clientId: String, item: EchoMediaItem, loaded: Boolean
        ) = ItemBottomSheet().apply {
            arguments = Bundle().apply {
                putString("clientId", clientId)
                putParcelable("item", item)
                putBoolean("loaded", loaded)
            }
        }
    }

    private var binding by autoCleared<DialogMediaItemBinding>()
    private val viewModel by viewModels<ItemViewModel>()
    private val playerViewModel by activityViewModels<PlayerViewModel>()
    private val args by lazy { requireArguments() }
    private val clientId by lazy { args.getString("clientId")!! }
    private val client by lazy { playerViewModel.extensionListFlow.getClient(clientId) }

    @Suppress("DEPRECATION")
    private val item by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            args.getParcelable("item", EchoMediaItem::class.java)!!
        else args.getParcelable("item")!!
    }
    private val loaded by lazy { args.getBoolean("loaded") }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = DialogMediaItemBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.itemContainer.run {
            more.run {
                setOnClickListener { dismiss() }
                setIconResource(R.drawable.ic_close)
                contentDescription = context.getString(R.string.close)
            }
            bind(item)
            if (!loaded) root.setOnClickListener {
                openItemFragment(item)
                dismiss()
            }
        }
        if (!loaded) {
            binding.recyclerView.adapter =
                ConcatAdapter(ActionAdapter(getActions(item, false)), LoadingAdapter())

            viewModel.item = item
            viewModel.client = client
            viewModel.initialize()
            observe(viewModel.itemFlow) {
                if (it != null) {
                    binding.itemContainer.bind(it)
                    binding.recyclerView.adapter = ActionAdapter(getActions(it, true))
                }
            }
        } else {
            binding.recyclerView.adapter = ActionAdapter(getActions(item, true))
        }
        observe(viewModel.shareLink) {
            requireContext().copyToClipboard(it, it)
        }
    }

    private fun openItemFragment(item: EchoMediaItem) {
        openFragment(ItemFragment.newInstance(clientId, item))
    }

    private fun getActions(item: EchoMediaItem, loaded: Boolean): List<ItemAction> = when (item) {
        is EchoMediaItem.Lists -> {
            listOfNotNull(
                ItemAction.Resource(R.drawable.ic_play, R.string.play) {
                    playerViewModel.play(clientId, item.tracks)
                },
                ItemAction.Resource(R.drawable.ic_playlist_play, R.string.play_next) {
                    playerViewModel.addToQueue(clientId, item.tracks, false)
                },
                ItemAction.Resource(R.drawable.ic_playlist_add, R.string.add_to_queue) {
                    playerViewModel.addToQueue(clientId, item.tracks, true)
                },
            ) + when (item) {
                is EchoMediaItem.Lists.AlbumItem -> {
                    listOfNotNull(
                        if (client is LibraryClient)
                            ItemAction.Resource(
                                R.drawable.ic_bookmark_outline, R.string.save_to_playlist
                            ) {
                                createSnack("Not implemented")
                            }
                        else null,
                        if (client is RadioClient)
                            ItemAction.Resource(R.drawable.ic_radio, R.string.radio) {
                                playerViewModel.radio(clientId, item.album)
                            }
                        else null,
                    )
                    item.album.artists.map {
                        ItemAction.Custom(it.cover, R.drawable.ic_artist, it.name) {
                            openItemFragment(it.toMediaItem())
                        }
                    }
                }

                is EchoMediaItem.Lists.PlaylistItem -> {
                    listOfNotNull(
                        if (client is RadioClient)
                            ItemAction.Resource(R.drawable.ic_radio, R.string.radio) {
                                playerViewModel.radio(clientId, item.playlist)
                            }
                        else null,
                        if (client is LibraryClient && item.playlist.isEditable)
                            ItemAction.Resource(R.drawable.ic_delete, R.string.delete_playlist) {
                                viewModel.deletePlaylist(clientId, item.playlist)
                            }
                        else null,
                    ) + item.playlist.authors.map {
                        ItemAction.Custom(it.cover, R.drawable.ic_artist, it.name) {
                            openItemFragment(it.toMediaItem())
                        }
                    }
                }
            }
        }

        is EchoMediaItem.Profile -> {
            if (item is EchoMediaItem.Profile.ArtistItem) listOfNotNull(
                if (client is RadioClient)
                    ItemAction.Resource(R.drawable.ic_radio, R.string.radio) {
                        playerViewModel.radio(clientId, item.artist)
                    }
                else null,
                if (client is ArtistFollowClient)
                    if (!item.artist.isFollowing)
                        ItemAction.Resource(R.drawable.ic_heart_outline_40dp, R.string.follow) {
                            createSnack("Not implemented")
                        }
                    else ItemAction.Resource(R.drawable.ic_heart_filled_40dp, R.string.unfollow) {
                        createSnack("Not implemented")
                    }
                else null
            )
            else listOf()
        }

        is EchoMediaItem.TrackItem -> {
            listOfNotNull(
                ItemAction.Resource(R.drawable.ic_play, R.string.play) {
                    playerViewModel.play(clientId, item.track)
                },
                ItemAction.Resource(R.drawable.ic_playlist_play, R.string.play_next) {
                    playerViewModel.addToQueue(clientId, item.track, false)
                },
                ItemAction.Resource(R.drawable.ic_playlist_add, R.string.add_to_queue) {
                    playerViewModel.addToQueue(clientId, item.track, true)
                },
                if (client is LibraryClient)
                    ItemAction.Resource(R.drawable.ic_bookmark_outline, R.string.save_to_playlist) {
                        createSnack("Not implemented")
                    }
                else null,
                if (client is RadioClient)
                    ItemAction.Resource(R.drawable.ic_radio, R.string.radio) {
                        playerViewModel.radio(clientId, item.track)
                    }
                else null,
                item.track.album?.let {
                    ItemAction.Custom(it.cover, R.drawable.ic_album, it.title) {
                        openItemFragment(it.toMediaItem())
                    }
                }
            ) + item.track.artists.map {
                ItemAction.Custom(it.cover, R.drawable.ic_artist, it.name) {
                    openItemFragment(it.toMediaItem())
                }
            }
        }
    } + listOfNotNull(
        if (client is ShareClient && loaded) ItemAction.Resource(
            R.drawable.ic_forward,
            R.string.share
        ) {
            val shareClient = client as ShareClient
            viewModel.onShare(shareClient, item)
        } else null
    )

    sealed class ItemAction {
        abstract val action: () -> Unit

        data class Resource(
            val resId: Int, val stringId: Int, override val action: () -> Unit
        ) : ItemAction()

        data class Custom(
            val image: ImageHolder?,
            val placeholder: Int,
            val title: String,
            override val action: () -> Unit
        ) : ItemAction()
    }

    inner class ActionAdapter(val list: List<ItemAction>) :
        RecyclerView.Adapter<ActionAdapter.ViewHolder>() {
        inner class ViewHolder(val binding: ItemDialogButtonBinding) :
            RecyclerView.ViewHolder(binding.root) {
            init {
                binding.root.setOnClickListener {
                    list[bindingAdapterPosition].action()
                    dismiss()
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemDialogButtonBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun getItemCount() = list.count()

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val action = list[position]
            val binding = holder.binding
            val colorState = ColorStateList.valueOf(
                binding.root.context.getColor(R.color.button_item)
            )
            when (action) {
                is ItemAction.Resource -> {
                    binding.textView.setText(action.stringId)
                    binding.imageView.setImageResource(action.resId)
                    binding.imageView.imageTintList = colorState
                }

                is ItemAction.Custom -> {
                    binding.textView.text = action.title
                    binding.imageView.imageTintList = colorState
                    action.image.loadInto(binding.imageView, action.placeholder)
                }
            }
        }
    }

    class LoadingAdapter : RecyclerView.Adapter<LoadingAdapter.ViewHolder>() {
        inner class ViewHolder(val binding: ItemDialogButtonLoadingBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemDialogButtonLoadingBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun getItemCount() = 1
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {}
    }
}