package me.timschneeberger.appdatabackup

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import android.widget.LinearLayout
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.appdatabackup.databinding.FragmentAppListBinding
import me.timschneeberger.appdatabackup.databinding.ItemAppBinding
import java.util.Locale
import java.util.concurrent.Executors
import androidx.core.content.edit


abstract class BaseAppListFragment : Fragment() {
    protected lateinit var adapter: PackageAdapter
    private lateinit var binding: FragmentAppListBinding

    private val loader = Executors.newSingleThreadExecutor()
    private var pkgsLoaded = false

    protected val prefs
        get() = context?.getSharedPreferences("app_list_prefs", Context.MODE_PRIVATE)

    abstract fun shouldShowPackage(info: PackageInfo): Boolean

    abstract fun queryPackageStatus(pkgName: String): String

    abstract fun onAppClicked(target: View, pkg: String)

    // Child classes may override this to provide per-row tool views (right aligned).
    protected open fun bindToolButtons(holder: VH, pkg: String): List<View>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentAppListBinding.inflate(inflater, container, false).also {
            binding = it
        }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        }

        loadPackages()

        // Register MenuProvider to provide the action bar search
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_app_list, menu)
                val searchItem = menu.findItem(R.id.action_search)
                val sv = searchItem?.actionView as? SearchView
                sv?.queryHint = getString(R.string.search)
                sv?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String?): Boolean = true
                    override fun onQueryTextChange(newText: String?): Boolean {
                        if (pkgsLoaded) adapter.filter.filter(newText)
                        return true
                    }
                })

               menu.findItem(R.id.action_reload)?.setOnMenuItemClickListener {
                    loadPackages()
                    true
                }

                val prefCleanRestore = menu.findItem(R.id.pref_clean_restore)
                prefCleanRestore.isChecked = prefs!!.getBoolean("pref_clean_restore", false)
                prefCleanRestore.setOnMenuItemClickListener {
                    val newValue = !prefCleanRestore.isChecked
                    prefCleanRestore.isChecked = newValue
                    prefs?.edit { putBoolean("pref_clean_restore", newValue) }
                    true
                }

                val prefShowAll = menu.findItem(R.id.pref_show_all)
                prefShowAll.isChecked = prefs?.getBoolean("show_all_apps", false) ?: false
                prefShowAll.setOnMenuItemClickListener {
                    val newValue = !prefShowAll.isChecked
                    prefShowAll.isChecked = newValue
                    prefs?.edit { putBoolean("show_all_apps", newValue) }
                    loadPackages()
                    true
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loader.shutdownNow()
    }

    private fun loadPackages() {
        // Load package list off the UI thread and show progress
        binding.progressBar.visibility = View.VISIBLE
        binding.recycler.visibility = View.GONE

        loader.execute {
            val pm = requireContext().packageManager
            val list = pm.getInstalledPackages(0)
                .filter { (prefs?.getBoolean("show_all_apps", false) ?: false) || shouldShowPackage(it) }
                .mapNotNull {
                    try {
                        val ai = it.applicationInfo ?: return@mapNotNull null
                        val label = pm.getApplicationLabel(ai).toString()
                        it.packageName to label
                    } catch (_: PackageManager.NameNotFoundException) { null }
                }
                .sortedBy { it.second.lowercase(Locale.getDefault()) }

            activity?.runOnUiThread {
                binding.recycler.apply {
                    adapter = PackageAdapter(requireContext(), list.toMutableList()).also {
                        this@BaseAppListFragment.adapter = it
                    }
                    visibility = View.VISIBLE
                }
                binding.progressBar.visibility = View.GONE
                pkgsLoaded = true

                Log.d("BaseAppListFragment", "Loaded ${list.size} packages")
            }
        }
    }

    protected class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    protected inner class PackageAdapter(val ctx: Context, val data: MutableList<Pair<String, String>>) : RecyclerView.Adapter<VH>(), Filterable {
        private val full = ArrayList<Pair<String, String>>(data)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                .let(::VH)

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (pkg, label) = data[position]
            holder.binding.apply {
                pkgLabel.text = label
                pkgName.text = pkg
                pkgStatus.text = queryPackageStatus(pkg)

                // Set app icon
                try {
                    pkgIcon.setImageDrawable(
                        ctx.packageManager.getApplicationIcon(
                            ctx.packageManager.getApplicationInfo(pkg, 0)
                        )
                    )
                } catch (_: Exception) {
                    pkgIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                // Populate optional tool buttons on the right, if provided by child class.
                val toolContainer = root.findViewById<LinearLayout>(R.id.tool_container)
                toolContainer.removeAllViews()
                val tools = bindToolButtons(holder, pkg)
                if (!tools.isNullOrEmpty()) {
                    // Add provided tool views and make container visible
                    tools.forEach { v ->
                        // Ensure proper layout params for horizontal container
                        if (v.layoutParams == null) {
                            v.layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                        }
                        toolContainer.addView(v)
                    }
                    toolContainer.visibility = View.VISIBLE
                } else {
                    // Hide container when no tools are present to preserve existing layout
                    toolContainer.visibility = View.GONE
                }
            }

            holder.itemView.setOnClickListener {
                onAppClicked(it, pkg)
            }
        }

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val q = constraint?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
                val res = FilterResults()
                if (q.isEmpty()) {
                    res.values = ArrayList(full)
                } else {
                    val filtered = full.filter {
                        it.second.lowercase(Locale.getDefault()).contains(q) || it.first.lowercase(
                            Locale.getDefault()
                        ).contains(q)
                    }
                    res.values = ArrayList(filtered)
                }
                return res
            }

            @SuppressLint("NotifyDataSetChanged")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                data.clear()
                @Suppress("UNCHECKED_CAST")
                data.addAll(results?.values as? List<Pair<String, String>> ?: emptyList())
                notifyDataSetChanged()
            }
        }

        fun removeItem(key: String) {
            val index = data.indexOfFirst { it.first == key }
            if (index != -1) {
                data.removeAt(index)
                full.removeIf { it.first == key }
                notifyItemRemoved(index)
            }
        }
    }
}
