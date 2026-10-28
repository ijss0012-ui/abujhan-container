package com.abujhan.container

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import java.io.File
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.abujhan.container.ui.MapPickerActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.abujhan.container.databinding.DialogProgressBinding
import androidx.recyclerview.widget.LinearLayoutManager
import com.abujhan.container.clone.CloneEngine
import com.abujhan.container.clone.CloneInstaller
import com.abujhan.container.clone.CloneProgress
import com.abujhan.container.clone.InstalledApps
import com.abujhan.container.clone.RootHelper
import com.abujhan.container.data.AppEntry
import com.abujhan.container.data.ContainerRecord
import com.abujhan.container.data.ContainerStore
import com.abujhan.container.databinding.ActivityMainBinding
import com.abujhan.container.location.Favorite
import com.abujhan.container.location.FavoriteStore
import com.abujhan.container.location.MockLocationService
import com.abujhan.container.location.MockPoint
import com.abujhan.container.location.MockLocationStore
import com.abujhan.container.ui.AppsAdapter
import com.abujhan.container.ui.ContainersAdapter
import com.abujhan.container.ui.MapLayers
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.tabs.TabLayout
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ContainerStore
    private val engine by lazy { CloneEngine(this) }

    private var allApps: List<AppEntry> = emptyList()
    private var rootAvailable = false

    private val appsAdapter = AppsAdapter { entry -> cloneApp(entry) }
    private val containersAdapter = ContainersAdapter(
        onLaunch = { record -> launchContainer(record) },
        onDelete = { record -> deleteContainer(record) },
        onSetLocation = { record -> showLocationDialog(record) },
    )

    private var pickCallback: ((Double, Double) -> Unit)? = null
    private var userMarker: Marker? = null
    private var centeredOn: GeoPoint? = null

    private val mapPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            pickCallback?.invoke(
                data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, 0.0),
                data.getDoubleExtra(MapPickerActivity.EXTRA_LNG, 0.0),
            )
        }
        pickCallback = null
    }

    private fun openMapPicker(lat: Double?, lng: Double?, onPick: (Double, Double) -> Unit) {
        pickCallback = onPick
        val intent = Intent(this, MapPickerActivity::class.java)
        if (lat != null && lng != null) {
            intent.putExtra(MapPickerActivity.EXTRA_LAT, lat)
            intent.putExtra(MapPickerActivity.EXTRA_LNG, lng)
        }
        mapPicker.launch(intent)
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            val record = store.get(pkg) ?: return
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                deleteRecordFiles(record)
                store.remove(pkg)
                refreshContainers()
                return
            }
            refreshContainers()
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    RootHelper.hideRoot(pkg)
                    RootHelper.grantLocationPermissions(pkg)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ContainerStore(this)

        binding.miniMap.setMultiTouchControls(true)
        binding.miniMap.controller.setZoom(15.0)
        binding.miniMap.setOnTouchListener { view, _ ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        binding.layerGroup.addOnButtonCheckedListener(
            MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@OnButtonCheckedListener
                setLayer(
                    when (checkedId) {
                        R.id.btnLayerSatellite -> MapLayers.SATELLITE
                        R.id.btnLayerTerrain -> MapLayers.TERRAIN
                        else -> MapLayers.STREET
                    },
                )
            },
        )

        binding.appsList.layoutManager = LinearLayoutManager(this)
        binding.appsList.adapter = appsAdapter
        binding.containersList.layoutManager = LinearLayoutManager(this)
        binding.containersList.adapter = containersAdapter

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPage(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = applyFilter(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.btnSaveLocation.setOnClickListener { saveDefaultLocation() }
        binding.btnPickMap.setOnClickListener {
            openMapPicker(
                binding.inputLat.text?.toString()?.toDoubleOrNull(),
                binding.inputLng.text?.toString()?.toDoubleOrNull(),
            ) { lat, lng ->
                binding.inputLat.setText(lat.toString())
                binding.inputLng.setText(lng.toString())
            }
        }
        binding.btnToggleLocation.setOnClickListener {
            if (MockLocationStore.isActive(this)) stopLocation() else enableLocation()
        }
        binding.btnAddFavorite.setOnClickListener { addFavorite() }
        binding.swipeApps.setOnRefreshListener { loadApps() }
        binding.btnHideRoot.setOnClickListener { hideRootForContainers() }

        loadDefaultLocation()
        refreshFavorites()
        showPage(0)
        loadApps()
        refreshContainers()
        checkRoot()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CloneProgress.state.collect { renderProgress(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.miniMap.onResume()
        refreshStatus()
        pruneDeletedContainers()
    }

    private var progressDialog: AlertDialog? = null
    private var progressBinding: DialogProgressBinding? = null

    /** يعرض نافذة التقدم أثناء النسخ والتثبيت، ويحدّثها حتى تكتمل العملية أو تفشل. */
    private fun renderProgress(state: CloneProgress.State?) {
        if (state == null) {
            progressDialog?.dismiss()
            progressDialog = null
            progressBinding = null
            return
        }
        val dialog = progressDialog ?: createProgressDialog().also { progressDialog = it }
        val b = progressBinding ?: return
        val finished = state.phase == CloneProgress.Phase.DONE || state.phase == CloneProgress.Phase.FAILED
        dialog.setTitle(state.label)
        b.progressText.text = when (state.phase) {
            CloneProgress.Phase.CLONING -> getString(R.string.progress_cloning, state.label)
            CloneProgress.Phase.INSTALLING -> getString(R.string.progress_installing, state.label)
            CloneProgress.Phase.WAITING_CONFIRM -> getString(R.string.progress_waiting)
            CloneProgress.Phase.DONE -> getString(R.string.progress_done, state.label)
            CloneProgress.Phase.FAILED -> getString(R.string.progress_failed, state.message ?: "")
        }
        val indeterminate = state.percent < 0 && !finished
        b.progressBar.isIndeterminate = indeterminate
        if (!indeterminate) {
            b.progressBar.progress = if (finished) 100 else state.percent
        }
        b.progressPercent.text = when {
            indeterminate -> ""
            finished -> ""
            else -> "${b.progressBar.progress}%"
        }
        dialog.setCancelable(finished)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = finished
    }

    private fun createProgressDialog(): AlertDialog {
        val b = DialogProgressBinding.inflate(layoutInflater)
        progressBinding = b
        val dialog = AlertDialog.Builder(this)
            .setView(b.root)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_close) { _, _ -> CloneProgress.set(null) }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        return dialog
    }

    override fun onPause() {
        super.onPause()
        binding.miniMap.onPause()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(packageReceiver) }
    }

    // ------------------------------------------------------------------- UI

    private fun showPage(position: Int) {
        binding.pageApps.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding.pageContainers.visibility = if (position == 1) View.VISIBLE else View.GONE
        binding.pageLocation.visibility = if (position == 2) View.VISIBLE else View.GONE
    }

    private fun loadApps() {
        binding.appsProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            allApps = withContext(Dispatchers.IO) { InstalledApps.load(this@MainActivity) }
            applyFilter(binding.inputSearch.text?.toString().orEmpty())
            binding.appsProgress.visibility = View.GONE
            binding.swipeApps.isRefreshing = false
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) allApps else allApps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
        appsAdapter.submit(filtered)
    }

    private fun refreshContainers() {
        val list = store.all()
        containersAdapter.submit(list)
        binding.containersEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- root

    private fun checkRoot() {
        lifecycleScope.launch {
            rootAvailable = withContext(Dispatchers.IO) { RootHelper.hasRoot() }
            binding.textRootStatus.text = getString(
                if (rootAvailable) R.string.root_available else R.string.root_missing,
            )
        }
    }

    /**
     * يفعّل الموقع الوهمي من داخل ABUJHAN نفسه: يمنح الصلاحية عبر root
     * (بدون أي خطوة في خيارات المطوّر) ثم يشغّل الخدمة بالموقع المحفوظ.
     */
    private fun enableLocation() {
        val point = currentPoint() ?: run {
            toast("أدخل إحداثيات صالحة أولًا")
            return
        }
        lifecycleScope.launch {
            val rooted = withContext(Dispatchers.IO) { RootHelper.hasRoot() }
            if (!rooted) {
                // بدون روت: النسخ المستنسخة تقرأ الموقع من ABUJHAN مباشرة، فيكفي حفظ الحالة.
                MockLocationStore.save(this@MainActivity, point)
                MockLocationStore.setActive(this@MainActivity, true)
                toast("مفعّل للنسخ فقط، الجهاز بدون روت")
                refreshStatus()
                return@launch
            }
            val granted = withContext(Dispatchers.IO) {
                val ok = RootHelper.grantMockLocation(packageName)
                RootHelper.grantLocationPermissions(packageName)
                ok
            }
            if (!granted) {
                toast("تعذّر منح صلاحية الموقع الوهمي، تأكد من منح ABUJHAN صلاحية root")
                return@launch
            }
            MockLocationService.activate(this@MainActivity, point)
            toast(getString(R.string.mock_enabled_self))
            refreshStatus()
        }
    }

    /** يوقف الموقع الوهمي: يزيل مزوّدات الاختبار ويسحب الصلاحية ليعود الموقع الحقيقي. */
    private fun stopLocation() {
        MockLocationService.deactivate(this)
        refreshStatus()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { RootHelper.revokeMockLocation(packageName) }
            toast("تم إيقاف الموقع الوهمي")
        }
    }

    private fun setLayer(source: org.osmdroid.tileprovider.tilesource.ITileSource) {
        binding.miniMap.setTileSource(source)
    }

    /** يحدّث زر التفعيل: أخضر والموقع مفعّل، أحمر والموقع متوقف. */
    private fun updateToggle(active: Boolean) {
        binding.btnToggleLocation.setText(if (active) R.string.btn_toggle_off else R.string.btn_toggle_on)
        binding.btnToggleLocation.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (active) "#2E7D32" else "#C62828"))
        binding.btnToggleLocation.setTextColor(Color.WHITE)
    }

    /**
     * يضع دبوس «موقعي» في الخريطة المصغّرة: عند الموقع الوهمي يكون في الموقع الوهمي،
     * وإلا في الموقع الحقيقي الذي قرأناه آخر مرة.
     */
    private fun updateMiniMap(pin: GeoPoint?) {
        val map = binding.miniMap
        userMarker?.let { map.overlays.remove(it) }
        userMarker = null
        if (pin == null) {
            map.invalidate()
            return
        }
        userMarker = Marker(map).apply {
            position = pin
            title = getString(R.string.my_pin)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        map.overlays.add(userMarker)
        if (centeredOn != pin) {
            map.controller.animateTo(pin)
            centeredOn = pin
        }
        map.invalidate()
    }

    private fun realPin(): GeoPoint? {
        val prefs = getSharedPreferences("real_location", MODE_PRIVATE)
        val lat = prefs.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lng = prefs.getString("lng", null)?.toDoubleOrNull() ?: return null
        return GeoPoint(lat, lng)
    }

    private fun saveRealPin(lat: Double, lng: Double) {
        getSharedPreferences("real_location", MODE_PRIVATE).edit()
            .putString("lat", lat.toString())
            .putString("lng", lng.toString())
            .apply()
    }

    // ------------------------------------------------------------- favorites

    private fun addFavorite() {
        val lat = binding.inputLat.text?.toString()?.toDoubleOrNull()
        val lng = binding.inputLng.text?.toString()?.toDoubleOrNull()
        if (lat == null || lng == null) {
            toast("إحداثيات غير صالحة")
            return
        }
        val acc = binding.inputAccuracy.text?.toString()?.toFloatOrNull() ?: 5f
        val nameInput = EditText(this).apply { hint = getString(R.string.favorite_name_hint) }
        AlertDialog.Builder(this)
            .setView(nameInput.apply { setPadding(48, 24, 48, 24) })
            .setPositiveButton(R.string.btn_add_favorite) { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { "$lat, $lng" }
                FavoriteStore.add(this, Favorite(name, lat, lng, acc))
                refreshFavorites()
                toast(getString(R.string.favorite_added))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** يبني قائمة المفضلة: اضغط لتحميل الموقع في الحقول، واضغط مطولًا للحذف. */
    private fun refreshFavorites() {
        val list = binding.favoritesList
        list.removeAllViews()
        val favorites = FavoriteStore.all(this)
        if (favorites.isEmpty()) {
            list.addView(TextView(this).apply {
                setText(R.string.favorites_empty)
                setTextColor(getColor(R.color.muted))
                setPadding(8, 8, 8, 8)
            })
            return
        }
        favorites.forEachIndexed { index, fav ->
            list.addView(TextView(this).apply {
                text = "${fav.name}\n${fav.lat}, ${fav.lng}"
                textSize = 14f
                setPadding(16, 16, 16, 16)
                setBackgroundColor(0x22000000)
                setOnClickListener {
                    binding.inputLat.setText(fav.lat.toString())
                    binding.inputLng.setText(fav.lng.toString())
                    binding.inputAccuracy.setText(fav.accuracy.toString())
                    toast(getString(R.string.location_saved))
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.favorite_delete_title)
                        .setPositiveButton(R.string.btn_delete) { _, _ ->
                            FavoriteStore.remove(this@MainActivity, index)
                            refreshFavorites()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 8 }
                layoutParams = lp
            })
        }
    }

    /** الإحداثيات المعروضة في تبويب الموقع، أو المحفوظة إن لم تكن مدخلة. */
    private fun currentPoint(): MockPoint? {
        val lat = binding.inputLat.text?.toString()?.toDoubleOrNull()
        val lng = binding.inputLng.text?.toString()?.toDoubleOrNull()
        if (lat != null && lng != null) {
            val acc = binding.inputAccuracy.text?.toString()?.toFloatOrNull() ?: 5f
            return MockPoint(lat, lng, acc)
        }
        return MockLocationStore.load(this)
    }

    /** يخفي الروت عن كل النسخ فقط؛ الموقع الوهمي يأتي من ABUJHAN. */
    private fun hideRootForContainers() {
        val containers = store.all()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                containers.forEach { RootHelper.hideRoot(it.containerPackage) }
            }
            toast(getString(R.string.root_applied))
        }
    }

    // --------------------------------------------------------------- cloning

    private fun cloneApp(entry: AppEntry) {
        toast(getString(R.string.cloning))
        appsAdapter.setBusy(entry.packageName, true)
        CloneProgress.set(CloneProgress.State(entry.label, CloneProgress.Phase.CLONING))
        lifecycleScope.launch {
            try {
                val newPackage = engine.nextContainerPackage(entry.packageName)
                val output = withContext(Dispatchers.IO) { engine.build(entry, newPackage) }
                store.upsert(
                    ContainerRecord(
                        originalPackage = entry.packageName,
                        containerPackage = newPackage,
                        label = entry.label,
                        apkPaths = output.all.map { it.absolutePath },
                    )
                )
                refreshContainers()
                withContext(Dispatchers.IO) {
                    CloneInstaller.install(this@MainActivity, output.all, entry.label)
                }
            } catch (t: Throwable) {
                CloneProgress.set(
                    CloneProgress.State(
                        entry.label,
                        CloneProgress.Phase.FAILED,
                        message = t.message ?: t.javaClass.simpleName,
                    ),
                )
                toast(getString(R.string.clone_failed, t.message ?: t.javaClass.simpleName))
            } finally {
                appsAdapter.setBusy(entry.packageName, false)
            }
        }
    }

    /** يحذف ملفات APK للنسخة من مساحة التطبيق. */
    private fun deleteRecordFiles(record: ContainerRecord) {
        record.apkPaths.forEach { path -> runCatching { File(path).delete() } }
    }

    /**
     * يحذف السجلات التي طُلب حذفها بعد أن يتأكد النظام من إزالة النسخة.
     * إن بقيت النسخة مثبّتة (أُلغي الحذف) نزيل علامة الحذف فقط.
     */
    private fun pruneDeletedContainers() {
        store.all().filter { it.pendingDelete }.forEach { record ->
            if (CloneInstaller.isInstalled(this, record.containerPackage)) {
                store.setPendingDelete(record.containerPackage, false)
            } else {
                deleteRecordFiles(record)
                store.remove(record.containerPackage)
            }
        }
        refreshContainers()
    }

    private fun launchContainer(record: ContainerRecord) {
        if (!CloneInstaller.isInstalled(this, record.containerPackage)) {
            toast("النسخة غير مثبّتة على الجهاز")
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                RootHelper.forceStop(record.containerPackage)
                RootHelper.hideRoot(record.containerPackage)
                RootHelper.grantLocationPermissions(record.containerPackage)
                RootHelper.grantMockLocation(packageName)
            }
            // الموقع الوهمي للنظام كله: نفعّل الموقع المحدّد للحاوية كموقع نشط.
            if (record.hasLocation) {
                MockLocationService.activate(
                    this@MainActivity,
                    MockPoint(record.latitude!!, record.longitude!!, record.accuracy),
                )
            }
            val launched = CloneInstaller.launch(this@MainActivity, record.containerPackage)
            if (!launched) toast("تعذّر تشغيل النسخة")
        }
    }

    /**
     * يلغي تثبيت النسخة من الجهاز كاملة، ثم يحذف سجلها وملفاتها بعد التأكد من الإزالة.
     * على الجهاز المروّت تُلغى مباشرة، وإلا يفتح النظام نافذة الحذف.
     */
    private fun deleteContainer(record: ContainerRecord) {
        store.setPendingDelete(record.containerPackage, true)
        refreshContainers()
        lifecycleScope.launch {
            if (!CloneInstaller.isInstalled(this@MainActivity, record.containerPackage)) {
                pruneDeletedContainers()
                return@launch
            }
            val removed = withContext(Dispatchers.IO) {
                rootAvailable && RootHelper.uninstallPackage(record.containerPackage)
            }
            if (removed) {
                pruneDeletedContainers()
            } else {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${record.containerPackage}")))
            }
        }
    }

    // -------------------------------------------------------------- location

    private fun showLocationDialog(record: ContainerRecord) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val latInput = numberField("خط العرض", record.latitude?.toString() ?: "")
        val lngInput = numberField("خط الطول", record.longitude?.toString() ?: "")
        val accInput = numberField("الدقة (متر)", record.accuracy.toString())
        container.addView(android.widget.Button(this).apply {
            text = getString(R.string.btn_pick_map)
            setOnClickListener {
                openMapPicker(
                    latInput.text.toString().toDoubleOrNull(),
                    lngInput.text.toString().toDoubleOrNull(),
                ) { lat, lng ->
                    latInput.setText(lat.toString())
                    lngInput.setText(lng.toString())
                }
            }
        })
        container.addView(latInput)
        container.addView(lngInput)
        container.addView(accInput)

        AlertDialog.Builder(this)
            .setTitle(record.label)
            .setView(container)
            .setPositiveButton(R.string.location_save) { _, _ ->
                val lat = latInput.text.toString().toDoubleOrNull()
                val lng = lngInput.text.toString().toDoubleOrNull()
                if (lat == null || lng == null) {
                    toast("إحداثيات غير صالحة")
                    return@setPositiveButton
                }
                record.latitude = lat
                record.longitude = lng
                record.accuracy = accInput.text.toString().toFloatOrNull() ?: 5f
                store.upsert(record)
                refreshContainers()
                toast(getString(R.string.location_saved))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun numberField(hint: String, value: String): EditText = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
            InputType.TYPE_NUMBER_FLAG_SIGNED
        setText(value)
    }

    private fun saveDefaultLocation() {
        getSharedPreferences("defaults", MODE_PRIVATE).edit()
            .putString("lat", binding.inputLat.text.toString())
            .putString("lng", binding.inputLng.text.toString())
            .putString("accuracy", binding.inputAccuracy.text.toString())
            .apply()
        val lat = binding.inputLat.text.toString().toDoubleOrNull()
        val lng = binding.inputLng.text.toString().toDoubleOrNull()
        if (lat == null || lng == null) {
            toast("إحداثيات غير صالحة")
            return
        }
        toast(getString(R.string.location_saved))
    }

    /** حالة الموقع الوهمي: التفعيل، صلاحية النظام، وما يعرضه النظام فعلًا الآن. */
    private fun refreshStatus() {
        val active = MockLocationStore.isActive(this)
        val point = MockLocationStore.load(this)
        val permitted = isMockPermitted()
        val lm = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager
        val systemLocation = runCatching {
            lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        val systemMock = systemLocation?.let {
            if (android.os.Build.VERSION.SDK_INT >= 31) it.isMock else @Suppress("DEPRECATION") it.isFromMockProvider
        } == true
        if (systemLocation != null && !systemMock) saveRealPin(systemLocation.latitude, systemLocation.longitude)
        updateToggle(active)
        updateMiniMap(
            if (active && point != null) GeoPoint(point.lat, point.lng) else realPin(),
        )

        val lines = listOf(
            "الموقع الوهمي: " + if (active) "مفعّل" else "متوقف",
            "صلاحية ABUJHAN كمصدر وهمي: " + if (permitted) "ممنوحة" else "غير ممنوحة",
            "الموقع المحفوظ: " + (point?.let { "${it.lat}, ${it.lng}" } ?: "لا يوجد"),
            "الموقع الذي يعرضه النظام الآن: " +
                (systemLocation?.let { "${it.latitude}, ${it.longitude}" } ?: "غير متاح") +
                if (systemMock) " (وهمي ✔)" else " (حقيقي)",
        )
        binding.textLocationStatus.text = lines.joinToString("\n")
    }

    private fun isMockPermitted(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as? android.app.AppOpsManager ?: return false
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_MOCK_LOCATION,
            android.os.Process.myUid(),
            packageName,
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun loadDefaultLocation() {
        val prefs = getSharedPreferences("defaults", MODE_PRIVATE)
        binding.inputLat.setText(prefs.getString("lat", ""))
        binding.inputLng.setText(prefs.getString("lng", ""))
        binding.inputAccuracy.setText(prefs.getString("accuracy", "5"))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
