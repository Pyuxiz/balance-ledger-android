package com.example.icbcbalance.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.icbcbalance.data.*
import com.example.icbcbalance.parser.BankProfiles
import com.example.icbcbalance.service.IcBcNotificationListener
import com.example.icbcbalance.util.MoneyUtils
import com.example.icbcbalance.widget.WidgetCardRenderer
import com.example.icbcbalance.widget.balanceWidgetComponents
import com.example.icbcbalance.widget.balanceWidgetIds
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WalletApp() }
    }
}

private val Forest = Color(0xFF294F40)
private val Income = Color(0xFF287657)
private val Expense = Color(0xFFA65341)

@Composable
private fun WalletApp() {
    val context = LocalContext.current
    val repo = remember { BalanceRepository(context.applicationContext) }
    val wallet by repo.wallet.collectAsState(initial = WalletState())
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var panel by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<CardAccount?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CardAccount?>(null) }
    fun action(block: suspend () -> Unit) { scope.launch {
        try { block() } catch (e: Exception) { snack.showSnackbar(e.message ?: "操作未完成，请重试") }
    } }
    BackHandler(enabled = tab != 0 || panel.isNotBlank()) {
        if (panel.isNotBlank()) panel = "" else tab = 0
    }
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFABD2B8),
        primaryContainer = Color(0xFF304C3C), onPrimaryContainer = Color(0xFFDAEDDF),
        secondaryContainer = Color(0xFF354B3D), onSecondaryContainer = Color(0xFFD7EDDD),
        background = Color(0xFF151C18), surface = Color(0xFF1D2520), surfaceVariant = Color(0xFF2B352E))
        else lightColorScheme(primary = Forest, secondary = Color(0xFF697A6B),
            primaryContainer = Color(0xFFDDEADD), onPrimaryContainer = Forest,
            secondaryContainer = Color(0xFFE1EBDD), onSecondaryContainer = Forest,
            background = Color(0xFFF5F6F2), surface = Color(0xFFFEFFFC), surfaceVariant = Color(0xFFE9EEE7),
            onSurface = Color(0xFF202D25), onSurfaceVariant = Color(0xFF6C786F))
    MaterialTheme(colorScheme = colors, shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp))) {
        Scaffold(containerColor = colors.background, snackbarHost = { SnackbarHost(snack) }, bottomBar = {
            NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
                listOf("总览", "账单", "设置").forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; panel = "" },
                        icon = { NavGlyph(i) }, label = { Text(label) })
                }
            }
        }) { inset ->
            Box(Modifier.fillMaxSize().padding(inset), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (panel.isNotBlank() && tab == 2) TextButton(onClick = { panel = "" }) { Text("返回") }
                        Column(Modifier.weight(1f)) {
                            Text(when { tab == 0 -> "余额手账"; tab == 1 -> "收支明细"; panel.isNotBlank() -> panel; else -> "设置" },
                                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            if (panel.isBlank()) Text(listOf("把每一笔变化，记在这里", "按交易时间记录 · 仅供参考", "按你的习惯，安排每一处细节")[tab],
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        if (tab == 0) FilledTonalButton(onClick = { adding = true }) { Text("＋ 添加卡") }
                    }
                    when (tab) {
                        0 -> Overview(wallet, onEdit = { editing = it }, onSelect = { action { repo.selectAccount(it) } },
                            onSettings = { tab = 2; panel = "权限与接收" }, onBills = { tab = 1 })
                        1 -> LedgerScreen(wallet)
                        else -> when (panel) {
                            "小组件外观" -> WidgetSettings(wallet, onSave = { action { repo.setWidgetInstance(it) } })
                            "权限与接收" -> PermissionSettings(wallet, repo) { message -> scope.launch { snack.showSnackbar(message) } }
                            "卡源管理" -> AccountSettings(wallet, onAdd = { adding = true }, onEdit = { editing = it }, onDelete = { deleting = it })
                            else -> SettingsHome(wallet) { panel = it }
                        }
                    }
                }
            }
        }
        if (adding || editing != null) AccountEditor(editing, onDismiss = { adding = false; editing = null },
            onSave = { name, tail, pkg, sender, signature, cents ->
                val id = editing?.id
                action {
                    repo.saveAccount(id, name, tail, pkg, sender, signature, cents)
                    adding = false; editing = null
                    IcBcNotificationListener.refresh(context)
                    snack.showSnackbar("卡源已保存")
                }
            })
        deleting?.let { account -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这张卡？") },
            text = { Text("${account.name} · ${account.balance.cardLast4} 的本地余额和账单将一并删除。") },
            confirmButton = { TextButton(onClick = { action { repo.deleteAccount(account.id); deleting = null } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    }
}

@Composable
private fun NavGlyph(index: Int) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(23.dp)) {
        val stroke = Stroke(width = 1.7.dp.toPx())
        when (index) {
            0 -> { drawRoundRect(color, Offset(1.dp.toPx(), 4.dp.toPx()), Size(21.dp.toPx(), 15.dp.toPx()), CornerRadius(4.dp.toPx()), style = stroke)
                drawLine(color, Offset(2.dp.toPx(), 9.dp.toPx()), Offset(21.dp.toPx(), 9.dp.toPx()), 1.7.dp.toPx()) }
            1 -> { drawRoundRect(color, Offset(4.dp.toPx(), 1.dp.toPx()), Size(15.dp.toPx(), 21.dp.toPx()), CornerRadius(3.dp.toPx()), style = stroke)
                for (y in listOf(7, 12, 17)) drawLine(color, Offset(8.dp.toPx(), y.dp.toPx()), Offset(15.dp.toPx(), y.dp.toPx()), 1.7.dp.toPx()) }
            else -> { for ((i, y) in listOf(5, 12, 19).withIndex()) {
                drawLine(color, Offset(2.dp.toPx(), y.dp.toPx()), Offset(21.dp.toPx(), y.dp.toPx()), 1.7.dp.toPx())
                drawCircle(color, 3.dp.toPx(), Offset((if (i == 1) 15 else 8).dp.toPx(), y.dp.toPx()))
            } }
        }
    }
}

@Composable
private fun Overview(wallet: WalletState, onEdit: (CardAccount) -> Unit, onSelect: (String) -> Unit,
    onSettings: () -> Unit, onBills: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Forest), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("已记录资产 · CNY", color = Color(0xFFCCDCD0), style = MaterialTheme.typography.labelLarge)
                    Text(if (wallet.accounts.none { it.balance.confidence != BalanceConfidence.UNKNOWN }) "¥ —" else
                        "¥ ${total(wallet.accounts.filter { it.balance.confidence != BalanceConfidence.UNKNOWN }.map { it.balance.balanceCents })}",
                        color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Text("${wallet.accounts.size} 个卡源   ·   ${wallet.accounts.count { it.balance.confidence == BalanceConfidence.ESTIMATED }} 个余额为估算",
                        color = Color(0xFFCCDCD0), style = MaterialTheme.typography.bodySmall)
                    Text("本地记录，仅供参考", color = Color(0xFFB9CDBF), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item { Guidance(onSettings) }
        item { SectionTitle("我的卡片", "点击卡片切换当前卡") }
        if (wallet.accounts.isEmpty()) item { EmptyState("从一张卡开始", "添加卡尾号，配对银行通知，即可记录之后的收支。") }
        items(wallet.accounts, key = { it.id }) { account ->
            Card(onClick = { onSelect(account.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                            Text(account.name.take(1), Modifier.padding(horizontal = 13.dp, vertical = 10.dp), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("•••• ${account.balance.cardLast4}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onEdit(account) }) { Text("管理") }
                    }
                    Text(amount(account.balance), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(confidence(account.balance) + if (wallet.selected()?.id == account.id) " · 当前卡" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(time(account.balance.lastUpdatedAt, "MM/dd HH:mm"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("最近收支", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onBills) { Text("全部账单 →") }
        } }
        if (wallet.ledger.isEmpty()) item { EmptyState("等待第一笔记录", "先在银行 App 开启消费 / 动账通知，或开通银行短信通知。") }
        items(wallet.ledger.take(4), key = { it.id }) { entry -> LedgerRow(entry, wallet.accounts.find { it.id == entry.accountId }) }
    }
}

@Composable
private fun Guidance(onSettings: () -> Unit) {
    Surface(onClick = onSettings, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("让动账消息成为记录", style = MaterialTheme.typography.labelLarge)
            Text("请开启银行 App 的消费 / 动账通知，或银行短信通知，并允许通知内容显示。检查接收设置 →",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LedgerScreen(wallet: WalletState) {
    var accountId by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("全部") }
    val entries = wallet.ledger.filter { (accountId.isBlank() || it.accountId == accountId) &&
        (kind == "全部" || (kind == "收入") == (it.direction == Direction.INCOME)) }.sortedByDescending { it.transactionAt }
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = accountId.isBlank(), onClick = { accountId = "" }, label = { Text("所有卡源") })
            wallet.accounts.forEach { account -> FilterChip(selected = accountId == account.id, onClick = { accountId = account.id },
                label = { Text("${account.name} ${account.balance.cardLast4}") }) }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("全部", "收入", "支出").forEach {
            FilterChip(selected = kind == it, onClick = { kind = it }, label = { Text(it) })
        } } }
        item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) { Text("筛选内收入", style = MaterialTheme.typography.labelMedium)
                    Text("+${total(entries.filter { it.direction == Direction.INCOME }.map { it.amountCents })}", fontWeight = FontWeight.SemiBold) }
                Column(Modifier.weight(1f)) { Text("筛选内支出", style = MaterialTheme.typography.labelMedium)
                    Text("−${total(entries.filter { it.direction == Direction.EXPENSE }.map { it.amountCents })}", fontWeight = FontWeight.SemiBold) }
            }
        } }
        item { Text("数据来自银行通知或短信，仅供参考，不代表完整银行流水。重复来源会合并；仅记录已收到且可识别的消息。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (entries.isEmpty()) item { EmptyState("这里还没有账单", "消息到达并匹配卡源后，收支会按交易时间显示在这里。") }
        entries.groupBy { time(it.transactionAt, "yyyy年MM月dd日") }.forEach { (date, rows) ->
            item(key = date) { Text(date, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.id }) { LedgerRow(it, wallet.accounts.find { a -> a.id == it.accountId }) }
        }
    }
}

@Composable
private fun LedgerRow(entry: LedgerEntry, account: CardAccount?) {
    var expanded by remember { mutableStateOf(false) }
    Card(onClick = { expanded = !expanded }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(entry.description, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${account?.name.orEmpty()} · ${account?.balance?.cardLast4.orEmpty()}  ${time(entry.transactionAt, "HH:mm")}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${if (entry.direction == Direction.INCOME) "+" else "−"}${MoneyUtils.format(entry.amountCents)}",
                    fontWeight = FontWeight.SemiBold, color = if (entry.direction == Direction.INCOME) Income else Expense)
            }
            Text(entry.deliveries.joinToString(" · ") { deliveryLabel(it) } + " · 仅供参考",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("交易时间：${time(entry.transactionAt)}", style = MaterialTheme.typography.bodySmall)
                entry.balanceCents?.let { Text("消息所载余额：¥ ${MoneyUtils.format(it)}", style = MaterialTheme.typography.bodySmall) }
                if (entry.packageName.isNotBlank()) Text("来源应用：${entry.packageName}", style = MaterialTheme.typography.bodySmall)
                Text("接收时间：${time(entry.receivedAt)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsHome(wallet: WalletState, onOpen: (String) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsLink("卡源管理", "${wallet.accounts.size} 个卡源 · 配对通知、短信与余额校准", onOpen)
        SettingsLink("小组件外观", "卡源、配色、显示元素与隐私", onOpen)
        SettingsLink("权限与接收", "通知读取、短信、后台运行和接收诊断", onOpen)
        Spacer(Modifier.height(8.dp))
        Text("数据留在你的设备上", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("无需银行登录，不联网，不读取短信历史。账单来自通知或新到短信，可能遗漏、延迟；请以银行记录为准。内置 8 家银行来源模板，并对常见动账文案作保守识别。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("余额手账 2.2.0", Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsLink(title: String, subtitle: String, onOpen: (String) -> Unit) {
    Card(onClick = { onOpen(title) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun AccountSettings(wallet: WalletState, onAdd: () -> Unit, onEdit: (CardAccount) -> Unit, onDelete: (CardAccount) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加卡源") } }
        item { Text("每张卡独立保存余额与账单。配对已有通知即可选择银行 App，无需知道包名。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(wallet.accounts, key = { it.id }) { account -> Card {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${account.name} · ${account.balance.cardLast4}", fontWeight = FontWeight.SemiBold)
                Text(account.balance.configuredPackage.ifBlank { "尚未配对银行 App" }, style = MaterialTheme.typography.bodySmall)
                Row { TextButton(onClick = { onEdit(account) }) { Text("编辑 / 校准 / 配对") }
                    TextButton(onClick = { onDelete(account) }) { Text("删除", color = MaterialTheme.colorScheme.error) } }
            }
        } }
    }
}

private data class InstalledWidget(val id: Int, val widthDp: Int, val heightDp: Int)

@Composable
private fun WidgetSettings(wallet: WalletState, onSave: (WidgetInstanceOptions) -> Unit) {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    val owner = LocalLifecycleOwner.current
    var installed by remember { mutableStateOf<List<InstalledWidget>>(emptyList()) }
    var selectedId by rememberSaveable { mutableIntStateOf(0) }
    fun refreshInstances() {
        val ids = balanceWidgetIds(context)
        Log.d(BalanceRepository.TAG, "Configurable widget instances: ${ids.joinToString()}")
        installed = ids.map { id ->
            val bundle = manager.getAppWidgetOptions(id)
            val width = bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                .takeIf { it > 0 } ?: bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 } ?: 250
            val height = bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                .takeIf { it > 0 } ?: bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 110
            InstalledWidget(id, width, height)
        }
        if (installed.none { it.id == selectedId }) selectedId = installed.firstOrNull()?.id ?: 0
    }
    DisposableEffect(owner) {
        refreshInstances()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshInstances() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val selected = installed.find { it.id == selectedId }
    val options = wallet.widgetOptions(selectedId)
    val selectedAccounts = options.accountIds.ifEmpty { wallet.accounts.map { it.id } }
    val dark = isSystemInDarkTheme()
    val preview = remember(wallet, options, selected?.widthDp, selected?.heightDp, dark) {
        selected?.let { WidgetCardRenderer.render(context, wallet.widgetContent(options), options,
            it.widthDp.toFloat(), it.heightDp.toFloat()) }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("选择桌面上的小组件")
        if (installed.isEmpty()) {
            EmptyState("还没有可配置的小组件", "请先添加一个尺寸预设到桌面，再回到这里选择它。")
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                installed.forEachIndexed { index, item ->
                    FilterChip(selected = item.id == selectedId, onClick = { selectedId = item.id },
                        label = { Text("小组件 ${index + 1} · ${widgetSizeName(item.widthDp, item.heightDp)}") })
                }
            }
            preview?.let { bitmap ->
                Image(bitmap.asImageBitmap(), contentDescription = "小组件真实预览",
                    modifier = Modifier.fillMaxWidth().aspectRatio(selected!!.widthDp.toFloat() / selected.heightDp)
                        .heightIn(min = 54.dp, max = 260.dp), contentScale = ContentScale.FillBounds)
                Text("真实渲染预览 · 桌面画布 ${selected.widthDp}×${selected.heightDp} dp",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionTitle("合计哪些卡")
            Text(if (selectedAccounts.size == wallet.accounts.size) "当前显示全部卡源的总余额"
                else "当前合计 ${selectedAccounts.size} 张卡；最近交易取其中最新一笔",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            wallet.accounts.forEach { account ->
                val checked = account.id in selectedAccounts
                Row(Modifier.fillMaxWidth().clickable {
                    val next = if (checked) selectedAccounts - account.id else selectedAccounts + account.id
                    if (next.isNotEmpty()) onSave(options.copy(accountIds = next))
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { value ->
                        val next = if (value) selectedAccounts + account.id else selectedAccounts - account.id
                        if (next.isNotEmpty()) onSave(options.copy(accountIds = next))
                    })
                    Column(Modifier.weight(1f)) {
                        Text(account.name)
                        Text("•••• ${account.balance.cardLast4}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            SectionTitle("卡片配色")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "跟随系统", "paper" to "暖白", "sage" to "鼠尾草", "ink" to "墨绿").forEach { (id, name) ->
                    FilterChip(selected = options.style == id,
                        onClick = { onSave(options.copy(style = id, accountIds = selectedAccounts)) }, label = { Text(name) })
                }
            }
            Toggle("隐私模式：隐藏余额和交易金额", options.hideAmounts) {
                onSave(options.copy(hideAmounts = it, accountIds = selectedAccounts))
            }
        }

        SectionTitle("添加尺寸预设")
        Text("预设按宽×高标注。放置后仍可自由调整，内容会自动切换到最接近的布局。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        balanceWidgetComponents(context).zip(listOf("2×1", "2×2", "4×1", "4×2")).chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (component, name) ->
                    OutlinedButton(onClick = {
                        if (manager.isRequestPinAppWidgetSupported)
                            manager.requestPinAppWidget(component, null, null)
                    }, modifier = Modifier.weight(1f)) { Text("添加 $name") }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun widgetSizeName(width: Int, height: Int): String = when {
    width < 220 && height < 96 -> "2×1"
    width < 240 -> "2×2"
    height < 105 -> "4×1"
    else -> "4×2"
}

@Composable
private fun PermissionSettings(wallet: WalletState, repo: BalanceRepository, feedback: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var smsGranted by remember { mutableStateOf(false) }
    var listenerGranted by remember { mutableStateOf(false) }
    val connected by IcBcNotificationListener.connected.collectAsState()
    var pickSms by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    fun refresh() {
        smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        listenerGranted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    DisposableEffect(owner) {
        refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val requestSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        smsGranted = granted
        feedback(if (granted) "短信接收权限已授予" else "未授予短信权限；可在应用系统设置中开启")
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("请在银行 App 开启消费 / 动账通知，或银行短信通知，并允许通知显示完整内容。", style = MaterialTheme.typography.bodyMedium)
        SectionTitle("权限中心")
        PermissionCard("通知读取", if (connected) "已连接" else if (listenerGranted) "已授权，等待系统连接" else "未授权",
            "读取已配对银行 App 的动账通知；也可从系统短信通知识别余额。") {
            openSettings(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        PermissionCard("接收新短信", if (smsGranted) "已授权" else "未授权", "只接收新短信广播，不读取短信历史。部分系统仍可能拦截。") {
            requestSms.launch(Manifest.permission.RECEIVE_SMS)
        }
        PermissionCard("后台运行与系统设置", "按需检查", "若接收中断，请在系统中允许后台运行 / 自启动，并检查省电限制。不同设备入口可能不同。") {
            openSettings(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        SectionTitle("短信通知补充校准")
        Toggle("读取系统短信 App 的银行通知", wallet.smsNotificationFallback) {
            scope.launch { repo.setFallback(it); IcBcNotificationListener.refresh(context) }
        }
        Text("默认识别系统短信应用。需有完整卡尾号、交易方向、金额和银行身份；带余额时会校准，否则按当前余额暂记。折叠摘要、隐藏内容或受保护通知可能无法识别。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("系统短信应用：${IcBcNotificationListener.defaultSmsPackage(context) ?: "未获取，可手动配对"}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { pickSms = true }, modifier = Modifier.fillMaxWidth()) { Text("从通知中配对短信应用") }
        wallet.smsPackages.forEach { pkg -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pkg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { scope.launch { repo.removeSmsPackage(pkg) } }) { Text("移除") }
        } }
        SectionTitle("接收诊断")
        Card { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(wallet.lastResult, fontWeight = FontWeight.Medium)
            Text("短信广播：${time(wallet.lastSmsBroadcastAt)}", style = MaterialTheme.typography.bodySmall)
            Text("短信通知：${time(wallet.lastSmsNotificationAt)}", style = MaterialTheme.typography.bodySmall)
            Text("银行通知：${time(wallet.lastNotificationAt)}", style = MaterialTheme.typography.bodySmall)
            Text("权限已授予并不代表系统已送达消息。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        Button(onClick = {
            val active = IcBcNotificationListener.refresh(context)
            feedback(if (active) "已检查当前通知，请查看接收诊断和账单" else "已请求重新连接通知监听，请稍后再试")
        }, modifier = Modifier.fillMaxWidth()) { Text("重新检查通知栏里的消息") }
        Text("只补查仍在通知栏且 7 天内的消息，旧短信不会覆盖更新的手动校准。小组件刷新由桌面和系统调度，可能稍有延迟。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
    if (pickSms) SourcePicker(onDismiss = { pickSms = false }, onPick = { pkg ->
        scope.launch { repo.pairSmsPackage(pkg); pickSms = false; IcBcNotificationListener.refresh(context) }
    })
}

@Composable
private fun PermissionCard(title: String, status: String, body: String, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row { Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("前往设置 →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AccountEditor(account: CardAccount?, onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, Long?) -> Unit) {
    val initialProfile = remember(account) { BankProfiles.infer(account) }
    var profileId by remember { mutableStateOf(initialProfile?.id.orEmpty()) }
    var name by remember { mutableStateOf(account?.name ?: initialProfile?.name.orEmpty()) }
    var tail by remember { mutableStateOf(account?.balance?.cardLast4 ?: "") }
    var pkg by remember { mutableStateOf(account?.balance?.configuredPackage ?: "") }
    var sender by remember { mutableStateOf(account?.smsSender ?: initialProfile?.senderSetting.orEmpty()) }
    var signature by remember { mutableStateOf(account?.smsSignature ?: initialProfile?.signatureSetting.orEmpty()) }
    var balance by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (account == null) "添加卡源" else "管理卡源") },
        text = { Column(Modifier.heightIn(max = 490.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("银行模板", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BankProfiles.all.forEach { profile ->
                    FilterChip(selected = profileId == profile.id, onClick = {
                        profileId = profile.id
                        name = profile.name
                        sender = profile.senderSetting
                        signature = profile.signatureSetting
                    }, label = { Text(profile.name.removePrefix("中国")) })
                }
            }
            Text("模板会填入官方客服短信号码和常见落款；文案可能因卡种与服务而异。", style = MaterialTheme.typography.bodySmall)
            Field(name, { name = it }, "卡源名称，例如 建行 · 工资卡")
            OutlinedTextField(tail, { tail = it.filter { c -> c in '0'..'9' }.take(4) }, label = { Text("卡尾号（4 位）") },
                enabled = account == null, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("从通知选择银行 App") }
            Text(pkg.ifBlank { "尚未配对；也可只使用银行短信" }, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(balance, { balance = it }, label = { Text(if (account == null) "初始余额（可留空）" else "手动校准余额（不改则留空）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("留空时，新卡等待银行短信余额校准。已有卡的账单不会因手动校准清空。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起来源设置" else "短信签名与高级来源设置") }
            if (advanced) {
                Field(sender, { sender = it }, "银行短信号码（多个用逗号分隔）")
                Field(signature, { signature = it }, "短信落款（多个用逗号分隔）")
                Field(pkg, { pkg = it.trim() }, "银行 App 包名（可手动填写）")
                Text("支持工行精确格式，并识别常见的收入、支出、卡尾号、金额和余额字段。字段不足的消息不会自动入账。", style = MaterialTheme.typography.bodySmall)
            }
            Text("请先在银行 App 开启消费 / 动账通知，或银行短信通知。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            val cents = if (balance.isBlank()) null else MoneyUtils.parseMoneyToCents(balance)
            error = when { name.isBlank() -> "请填写卡源名称"; tail.length != 4 -> "请输入 4 位卡尾号"
                balance.isNotBlank() && cents == null -> "请输入有效余额，最多两位小数"
                pkg.isNotBlank() && !BalanceRepository.validPackage(pkg) -> "包名格式不正确"; else -> null }
            if (error == null) onSave(name, tail, pkg, sender, signature, cents)
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    if (picking) SourcePicker(onDismiss = { picking = false }, onPick = { pkg = it; picking = false })
}

@Composable
private fun SourcePicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val sources by IcBcNotificationListener.availableApps.collectAsState()
    var query by remember { mutableStateOf("") }
    val connected by IcBcNotificationListener.connected.collectAsState()
    LaunchedEffect(Unit) { IcBcNotificationListener.refresh(context) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择通知来源") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("以下为通知栏或本次监听见过的应用。选择后自动记录包名，不保存未配对应用的消息正文。", style = MaterialTheme.typography.bodySmall)
            Field(query, { query = it }, "搜索应用名称或包名")
            if (!connected) Text("通知监听尚未连接，请先在权限中心开启。", color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.heightIn(max = 330.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sources.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }, key = { it.packageName }) { source ->
                    Surface(onClick = { onPick(source.packageName) }, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(source.label, fontWeight = FontWeight.Medium)
                            Text(source.packageName, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (sources.isEmpty()) item { Text("暂时没有来源。请保留一条该应用的通知，然后刷新。") }
            }
        }
    }, confirmButton = { TextButton(onClick = { IcBcNotificationListener.refresh(context) }) { Text("刷新") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable private fun Field(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
@Composable private fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
@Composable private fun SectionTitle(text: String, end: String = "") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (end.isNotBlank()) Text(end, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}
private fun amount(state: BalanceState) = if (state.confidence == BalanceConfidence.UNKNOWN) "¥ —" else "¥ ${MoneyUtils.format(state.balanceCents)}"
private fun confidence(state: BalanceState) = when (state.confidence) {
    BalanceConfidence.UNKNOWN -> "等待初始余额"
    BalanceConfidence.MANUAL_CONFIRMED -> "手动已校准"
    BalanceConfidence.BANK_CONFIRMED -> "银行消息已校准"
    BalanceConfidence.ESTIMATED -> "本地估算 · ${state.provisionalTransactionCount} 笔"
}
private fun deliveryLabel(delivery: Delivery) = when (delivery) {
    Delivery.BANK_NOTIFICATION -> "来源：银行 App 通知"
    Delivery.SMS_BROADCAST -> "来源：银行短信"
    Delivery.SMS_NOTIFICATION -> "来源：短信通知"
}
private fun time(millis: Long, pattern: String = "MM-dd HH:mm") = if (millis <= 0) "暂无" else
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))
private fun total(values: List<Long>): String = java.text.DecimalFormat("#,##0.00").format(
    values.fold(BigDecimal.ZERO) { sum, v -> sum + BigDecimal.valueOf(v, 2) })
private fun openSettings(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
    }
}
