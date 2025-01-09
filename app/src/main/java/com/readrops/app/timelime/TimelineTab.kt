package com.readrops.app.timelime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import com.readrops.app.MainActivity
import com.readrops.app.R
import com.readrops.app.item.ItemScreen
import com.readrops.app.timelime.dialog.ErrorListDialog
import com.readrops.app.timelime.dialog.FilterBottomSheet
import com.readrops.app.timelime.dialog.OpenInParameterDialog
import com.readrops.app.timelime.drawer.TimelineDrawer
import com.readrops.app.util.components.CenteredProgressIndicator
import com.readrops.app.util.components.Placeholder
import com.readrops.app.util.components.RefreshScreen
import com.readrops.app.util.components.dialog.TwoChoicesDialog
import com.readrops.app.util.extensions.isError
import com.readrops.app.util.extensions.isLoading
import com.readrops.app.util.extensions.isNotEmpty
import com.readrops.app.util.extensions.openInCustomTab
import com.readrops.app.util.extensions.openUrl
import com.readrops.app.util.theme.spacing
import com.readrops.db.entities.OpenIn
import com.readrops.db.filters.MainFilter
import com.readrops.db.filters.OrderField
import com.readrops.db.filters.OrderType
import com.readrops.db.filters.SubFilter
import com.readrops.db.pojo.ItemWithFeed
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow


object TimelineTab : Tab {

    private val openItemChannel = Channel<Int>()

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 1u,
            title = stringResource(id = R.string.timeline),
        )

    @SuppressLint("InlinedApi")
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        val screenModel = koinScreenModel<TimelineScreenModel>()
        val state by screenModel.timelineState.collectAsStateWithLifecycle()
        val preferences = state.preferences
        val items = state.itemState.collectAsLazyPagingItems()

        val lazyListState = rememberLazyListState()
        val snackbarHostState = remember { SnackbarHostState() }
        val topAppBarState = rememberTopAppBarState()
        val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topAppBarState)

        val lazyColumnPadding = if (preferences.itemSize == TimelineItemSize.COMPACT) {
            0.dp
        } else {
            MaterialTheme.spacing.shortSpacing
        }

        val launcher =
            rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) {
                screenModel.disableDisplayNotificationsPermission()
            }

        LaunchedEffect(Unit) {
            openItemChannel.receiveAsFlow()
                .collect { itemId ->
                    screenModel.selectItemWithFeed(itemId)
                        ?.let { openItem(it, preferences, navigator, context) }
                }
        }

        LaunchedEffect(preferences.displayNotificationsPermission) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && preferences.displayNotificationsPermission
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        LaunchedEffect(state.scrollToTop) {
            if (state.scrollToTop) {
                lazyListState.scrollToItem(0)
                screenModel.resetScrollToTop()
                topAppBarState.contentOffset = 0f
            }
        }

        // remove splash screen when opening the app
        LaunchedEffect(items.isLoading()) {
            val activity = (context as MainActivity)
            if (!items.isLoading() && !activity.ready) {
                activity.ready = true
            }
        }

        val drawerState = rememberDrawerState(
            initialValue = DrawerValue.Closed,
            confirmStateChange = {
                if (it == DrawerValue.Closed) {
                    screenModel.closeDrawer()
                } else {
                    screenModel.openDrawer()
                }

                true
            }
        )

        BackHandler(
            enabled = state.isDrawerOpen,
            onBack = { screenModel.closeDrawer() }
        )

        LaunchedEffect(state.isDrawerOpen) {
            if (state.isDrawerOpen) {
                drawerState.open()
            } else {
                drawerState.close()
            }
        }

        LaunchedEffect(state.localSyncErrors) {
            if (state.localSyncErrors != null) {
                val action = snackbarHostState.showSnackbar(
                    message = context.resources.getQuantityString(
                        R.plurals.error_occurred,
                        state.localSyncErrors!!.size
                    ),
                    actionLabel = context.getString(R.string.details),
                    duration = SnackbarDuration.Short
                )

                if (action == SnackbarResult.ActionPerformed) {
                    screenModel.openDialog(DialogState.ErrorList(state.localSyncErrors!!))
                } else {
                    // remove errors from state
                    screenModel.closeDialog(DialogState.ErrorList(state.localSyncErrors!!))
                }
            }
        }

        LaunchedEffect(state.syncError) {
            if (state.syncError != null) {
                snackbarHostState.showSnackbar(state.syncError!!)
                screenModel.resetSyncError()
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                TimelineDrawer(
                    state = state,
                    onClickDefaultItem = {
                        screenModel.updateDrawerDefaultItem(it)
                    },
                    onFolderClick = {
                        screenModel.updateDrawerFolderSelection(it)
                    },
                    onFeedClick = {
                        screenModel.updateDrawerFeedSelection(it)
                    }
                )
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = when (state.filters.mainFilter) {
                                        MainFilter.STARS -> stringResource(R.string.favorites)
                                        MainFilter.ALL -> stringResource(R.string.articles)
                                        MainFilter.NEW -> stringResource(R.string.new_articles)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (state.showSubtitle) {
                                    Text(
                                        text = when (state.filters.subFilter) {
                                            SubFilter.FEED -> state.filterFeedName
                                            SubFilter.FOLDER -> state.filterFolderName
                                            else -> ""
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = { screenModel.openDrawer() }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = null
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { screenModel.openDialog(DialogState.FilterSheet) }
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_filter_list),
                                    contentDescription = null
                                )
                            }

                            IconButton(
                                onClick = { screenModel.refreshTimeline() }
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_sync),
                                    contentDescription = null
                                )
                            }
                        },
                        scrollBehavior = topAppBarScrollBehavior
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                floatingActionButton = {
                    if (!state.hideReadAllFAB) {
                        FloatingActionButton(
                            onClick = {
                                if (state.filters.mainFilter == MainFilter.ALL) {
                                    screenModel.openDialog(DialogState.ConfirmDialog)
                                } else {
                                    screenModel.setAllItemsRead()
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_done_all),
                                contentDescription = null
                            )
                        }
                    }
                },
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize()
                        .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                ) {
                    when {
                        state.displayRefreshScreen -> RefreshScreen(
                            currentFeed = state.currentFeed,
                            feedCount = state.feedCount,
                            feedMax = state.feedMax
                        )

                        items.isLoading() -> {
                            CenteredProgressIndicator()
                        }

                        items.isError() -> {
                            Placeholder(
                                text = stringResource(R.string.error_occured),
                                painter = painterResource(id = R.drawable.ic_error)
                            )
                        }

                        else -> {
                            PullToRefreshBox(
                                isRefreshing = state.isRefreshing,
                                onRefresh = { screenModel.refreshTimeline() },
                            ) {
                                if (items.isNotEmpty()) {
                                    MarkItemsRead(
                                        lazyListState = lazyListState,
                                        items = items,
                                        markReadOnScroll = preferences.markReadOnScroll,
                                        screenModel = screenModel
                                    )

                                    LazyColumn(
                                        state = lazyListState,
                                        contentPadding = PaddingValues(vertical = lazyColumnPadding),
                                        verticalArrangement = Arrangement.spacedBy(lazyColumnPadding)
                                    ) {
                                        items(
                                            count = items.itemCount,
                                            key = items.itemKey { it.item.id },
                                        ) { index ->
                                            val itemWithFeed = items[index]

                                            if (itemWithFeed != null) {
                                                TimelineItem(
                                                    itemWithFeed = itemWithFeed,
                                                    onClick = {
                                                        if (itemWithFeed.openInAsk && preferences.openInAsk) {
                                                            screenModel.openDialog(
                                                                DialogState.OpenIn(
                                                                    itemWithFeed
                                                                )
                                                            )
                                                        } else {
                                                            screenModel.setItemRead(itemWithFeed.item)

                                                            openItem(
                                                                itemWithFeed = itemWithFeed,
                                                                preferences = preferences,
                                                                navigator = navigator,
                                                                context = context
                                                            )
                                                        }
                                                    },
                                                    onFavorite = {
                                                        screenModel.updateStarState(itemWithFeed.item)
                                                    },
                                                    onShare = {
                                                        screenModel.shareItem(
                                                            itemWithFeed.item,
                                                            context
                                                        )
                                                    },
                                                    onSetReadState = {
                                                        screenModel.updateItemReadState(itemWithFeed.item)
                                                    },
                                                    size = preferences.itemSize
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // Empty lazyColumn to let the pull to refresh be usable
                                    // when no items are displayed
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize()
                                    ) {}

                                    Placeholder(
                                        text = stringResource(R.string.no_article),
                                        painter = painterResource(R.drawable.ic_timeline),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            when (val dialog = state.dialog) {
                is DialogState.ConfirmDialog -> {
                    TwoChoicesDialog(
                        title = stringResource(R.string.mark_all_articles_read),
                        text = stringResource(R.string.mark_all_articles_read_question),
                        icon = painterResource(id = R.drawable.ic_rss_feed_grey),
                        confirmText = stringResource(id = R.string.validate),
                        dismissText = stringResource(id = R.string.cancel),
                        onDismiss = { screenModel.closeDialog() },
                        onConfirm = {
                            screenModel.closeDialog()
                            screenModel.setAllItemsRead()
                        }
                    )
                }

                is DialogState.FilterSheet -> {
                    FilterBottomSheet(
                        filters = state.filters,
                        onSetShowReadItems = {
                            screenModel.setShowReadItemsState(!state.filters.showReadItems)
                        },
                        onSetOrderField = {
                            screenModel.setOrderFieldState(
                                if (state.filters.orderField == OrderField.ID) {
                                    OrderField.DATE
                                } else {
                                    OrderField.ID
                                }
                            )
                        },
                        onSetOrderType = {
                            screenModel.setOrderTypeState(
                                if (state.filters.orderType == OrderType.DESC) {
                                    OrderType.ASC
                                } else {
                                    OrderType.DESC
                                }
                            )
                        },
                        onDismiss = { screenModel.closeDialog() }
                    )
                }

                is DialogState.ErrorList -> {
                    ErrorListDialog(
                        errorResult = dialog.errorResult,
                        onDismiss = { screenModel.closeDialog(dialog) }
                    )
                }

                is DialogState.OpenIn -> {
                    val itemWithFeed = dialog.itemWithFeed

                    OpenInParameterDialog(
                        openIn = itemWithFeed.openIn!!,
                        onValidate = { openIn, openInAsk ->
                            screenModel.updateOpenInParameter(
                                feedId = itemWithFeed.feedId,
                                openIn = openIn,
                                openInAsk = openInAsk
                            )

                            screenModel.closeDialog(dialog)

                            openItem(
                                itemWithFeed = itemWithFeed,
                                openIn = openIn,
                                preferences = preferences,
                                navigator = navigator,
                                context = context
                            )
                            screenModel.setItemRead(itemWithFeed.item)
                        },
                        onDismiss = { screenModel.closeDialog(dialog) }
                    )
                }

                else -> {}
            }
        }
    }

    private fun openItem(
        itemWithFeed: ItemWithFeed,
        preferences: TimelinePreferences,
        navigator: Navigator,
        context: Context,
        openIn: OpenIn? = itemWithFeed.openIn,
    ) {
        val url = itemWithFeed.item.link!!

        if (openIn == OpenIn.LOCAL_VIEW) {
            navigator.push(ItemScreen(itemWithFeed.item.id))
        } else {
            if (preferences.openInExternalBrowser) {
                context.openUrl(url)
            } else {
                context.openInCustomTab(url, preferences.theme, Color(itemWithFeed.color))
            }
        }
    }

    @Composable
    private fun MarkItemsRead(
        lazyListState: LazyListState,
        items: LazyPagingItems<ItemWithFeed>,
        markReadOnScroll: Boolean,
        screenModel: TimelineScreenModel
    ) {
        val lastFirstVisibleItemIndex by screenModel.listIndexState.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            snapshotFlow { lazyListState.firstVisibleItemIndex }
                .filter {
                    if (it < lastFirstVisibleItemIndex) {
                        screenModel.updateLastFirstVisibleItemIndex(it)
                    }

                    it > lastFirstVisibleItemIndex
                }
                .collect { newLastFirstVisibleItemIndex ->
                    if (newLastFirstVisibleItemIndex - lastFirstVisibleItemIndex > 1) {
                        val difference = newLastFirstVisibleItemIndex - lastFirstVisibleItemIndex

                        for (subCount in 0 until difference) {
                            val item = items[lastFirstVisibleItemIndex + subCount]?.item

                            if (item != null && !item.isRead && markReadOnScroll) {
                                screenModel.setItemRead(item)
                            }
                        }
                    } else {
                        val item = items[lastFirstVisibleItemIndex]?.item

                        if (item != null && !item.isRead && markReadOnScroll) {
                            screenModel.setItemRead(item)
                        }
                    }

                    screenModel.updateLastFirstVisibleItemIndex(newLastFirstVisibleItemIndex)
                }
        }
    }

    suspend fun openItem(itemId: Int) {
        openItemChannel.send(itemId)
    }
}