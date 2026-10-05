package com.vangoghtimeline.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import com.vangoghtimeline.model.Artwork
import com.vangoghtimeline.model.MetaTagger
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vangoghtimeline.model.ColorMode
import com.vangoghtimeline.model.DefinitionTier
import com.vangoghtimeline.model.RightsKind
import com.vangoghtimeline.model.NamedColor
import com.vangoghtimeline.model.Subject
import com.vangoghtimeline.model.TagFilter
import com.vangoghtimeline.model.Technique

/** Les œuvres retenues par les filtres, la barre qui les règle et le message quand rien ne correspond. */
internal class WorkFilter(val artworks: List<Artwork>, val active: Boolean, val emptyText: String, val bar: @Composable () -> Unit)

/**
 * Les filtres choisis, partagés par TOUT l'écran : réglés dans la vue générale (menu des peintres), ils s'appliquent à la frise et à la carte du
 * peintre ouvert, ou à toutes les œuvres de tous les peintres. Le peintre n'est qu'un paramètre de recherche parmi les autres.
 */
@androidx.compose.runtime.Stable
class FilterState(initial: TagFilter = TagFilter()) {
    var filter by mutableStateOf(initial)

    companion object {
        val Saver: androidx.compose.runtime.saveable.Saver<FilterState, List<String>> = androidx.compose.runtime.saveable.Saver(
            save = { s -> with(s.filter) { listOf(subject?.name, technique?.name, mode?.name, hue?.name, rights?.name, definition?.name).map { it.orEmpty() } } },
            restore = { l ->
                fun at(i: Int) = l.getOrNull(i)?.takeIf { it.isNotEmpty() }
                FilterState(TagFilter(
                    at(0)?.let { Subject.valueOf(it) }, at(1)?.let { Technique.valueOf(it) }, at(2)?.let { ColorMode.valueOf(it) },
                    at(3)?.let { NamedColor.valueOf(it) }, at(4)?.let { RightsKind.valueOf(it) }, at(5)?.let { DefinitionTier.valueOf(it) },
                ))
            },
        )
    }
}

@Composable
fun rememberFilterState(): FilterState = rememberSaveable(saver = FilterState.Saver) { FilterState() }

/**
 * Applique [state] à [all] et fournit la barre de filtres (sujet / technique, licence, définition, couleur — voir [StyleIndex] pour la couleur).
 * [analyseAlways] : analyser les vignettes dès l'affichage (frise d'un peintre) ; sinon seulement quand un filtre de couleur est choisi (vue générale,
 * où il y a les œuvres de tous les peintres).
 */
@Composable
internal fun rememberWorkFilter(all: List<Artwork>, state: FilterState, analyseAlways: Boolean = true): WorkFilter {
    val context = LocalContext.current
    val metas = remember(all) { all.associate { it.id to MetaTagger.tag(it) } }
    val tiers = remember(all) { all.associate { it.id to DefinitionTier.of(it) } }
    val styleVersion = StyleIndex.version
    val filter = state.filter
    val analyse = analyseAlways || filter.needsPixels
    LaunchedEffect(all, analyse) { if (analyse) StyleIndex.index(context, context.imageLoader, all) }
    val shown = remember(all, filter, styleVersion) {
        if (!filter.active) all else all.filter { filter.matches(metas.getValue(it.id), StyleIndex.get(it.id), it.rights?.kind, tiers[it.id]) }
    }
    val subjectCounts = remember(metas) { metas.values.groupingBy { it.subject }.eachCount() }
    val techniqueCounts = remember(metas) { metas.values.groupingBy { it.technique }.eachCount() }
    val rightsCounts = remember(all) { all.groupingBy { it.rights?.kind ?: RightsKind.UNKNOWN }.eachCount() }
    val definitionCounts = remember(tiers) { tiers.values.groupingBy { it }.eachCount() }
    val indexed = StyleIndex.indexedCount(all)
    val empty = if (filter.needsPixels && indexed < all.size) "Aucune œuvre analysée ne correspond pour l'instant — l'analyse des couleurs continue…" else "Aucune œuvre ne correspond à ces filtres."
    return WorkFilter(shown, filter.active, empty) {
        FilterBar(
            filter, { state.filter = it },
            subjectCounts, techniqueCounts, rightsCounts, definitionCounts, shown = shown.size, total = all.size,
            indexed = if (analyse) indexed else all.size,
        )
    }
}

private val Gold = Color(0xFFF0D58A)
private val Line = Color(0x40FFFFFF)

/**
 * Filtres de la frise : sujet, technique, couleur (couleur / noir et blanc, et couleur dominante). Replié : une ligne « Filtres » et le nombre
 * d'œuvres retenues ; déplié : trois rangées de puces défilantes. Un toucher choisit, un second toucher retire. Les filtres de couleur dépendent de
 * l'analyse des vignettes ([StyleIndex]) : tant qu'elle n'est pas finie, [indexed] / [total] le dit et seules les œuvres analysées sont retenues.
 */
@Composable
internal fun FilterBar(
    filter: TagFilter, onFilter: (TagFilter) -> Unit,
    subjectCounts: Map<Subject, Int>, techniqueCounts: Map<Technique, Int>,
    rightsCounts: Map<RightsKind, Int>, definitionCounts: Map<DefinitionTier, Int>,
    shown: Int, total: Int, indexed: Int,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val currentFilter by rememberUpdatedState(onFilter)
    val activeCount = listOf(filter.subject, filter.technique, filter.mode, filter.hue, filter.rights, filter.definition).count { it != null }
    Column(modifier.fillMaxWidth().background(Color(0xB314171B)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText(
                "Filtres" + (if (activeCount > 0) " ($activeCount)" else "") + if (open) " ▲" else " ▼",
                style = TextStyle(color = if (activeCount > 0) Gold else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { open = !open }) }.padding(vertical = 4.dp),
            )
            if (filter.active) {
                BasicText("$shown / $total œuvres", style = TextStyle(color = Color(0xFFE6E9ED), fontSize = 12.sp))
                BasicText("Tout afficher", style = TextStyle(color = Gold, fontSize = 12.sp),
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { currentFilter(TagFilter()) }) }.padding(vertical = 4.dp))
            }
            if (indexed < total) BasicText("couleurs analysées : $indexed / $total", style = TextStyle(color = Color(0xFF8B96A3), fontSize = 11.sp))
        }
        if (open) {
            Group("Sujet") {
                for (s in Subject.values()) Chip(s.label, subjectCounts[s], filter.subject == s) { currentFilter(filter.copy(subject = if (filter.subject == s) null else s)) }
            }
            Group("Technique") {
                for (t in Technique.values()) Chip(t.label, techniqueCounts[t], filter.technique == t) { currentFilter(filter.copy(technique = if (filter.technique == t) null else t)) }
            }
            Group("Licence") {
                for (r in RightsKind.values()) Chip(r.labelFr.replaceFirstChar { it.uppercase() }, rightsCounts[r] ?: 0, filter.rights == r) { currentFilter(filter.copy(rights = if (filter.rights == r) null else r)) }
            }
            Group("Définition") {
                for (d in DefinitionTier.values()) Chip(d.label, definitionCounts[d] ?: 0, filter.definition == d) { currentFilter(filter.copy(definition = if (filter.definition == d) null else d)) }
            }
            Group("Couleur") {
                for (m in ColorMode.values()) Chip(m.label, null, filter.mode == m) { currentFilter(filter.copy(mode = if (filter.mode == m) null else m)) }
                for (c in NamedColor.values()) Chip(c.label, null, filter.hue == c, swatch = Color(0xFF000000.toInt() or c.rgb)) { currentFilter(filter.copy(hue = if (filter.hue == c) null else c)) }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = TextStyle(color = Color(0xFF8B96A3), fontSize = 11.sp), modifier = Modifier.width(66.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun Chip(label: String, count: Int?, on: Boolean, swatch: Color? = null, onTap: () -> Unit) {
    val currentTap by rememberUpdatedState(onTap)
    Row(
        Modifier
            .border(BorderStroke(1.dp, if (on) Gold else Line), RoundedCornerShape(10.dp))
            .background(if (on) Color(0x33F0D58A) else Color.Transparent, RoundedCornerShape(10.dp))
            .pointerInput(label) { detectTapGestures(onTap = { currentTap() }) }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) { Spacer(Modifier.size(10.dp).background(swatch, CircleShape).border(BorderStroke(1.dp, Line), CircleShape)); Spacer(Modifier.width(5.dp)) }
        BasicText(label + (count?.let { " ($it)" } ?: ""), maxLines = 1, style = TextStyle(color = if (on) Gold else Color.White, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal))
    }
}
