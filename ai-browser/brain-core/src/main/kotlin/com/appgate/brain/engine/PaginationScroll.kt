package com.appgate.brain.engine

import com.appgate.brain.model.*

/** CSS viewport geometry is a prerequisite only; it never proves clickability or success. */
internal object PaginationScroll {
    fun identity(a: Affordance): String = listOf(a.id,a.role.name,a.tag,a.facetKey.orEmpty(),
        a.hrefPath.orEmpty(),a.name,a.regionRole.name).joinToString("|")
    fun sameContext(a: SemanticPageState, b: SemanticPageState): Boolean =
        a.documentId.isNotBlank() && a.documentId == b.documentId && a.url == b.url && a.host == b.host &&
            a.resultKeys == b.resultKeys && a.viewportHeight == b.viewportHeight && a.viewportWidth == b.viewportWidth

    fun following(program: List<Step>, index: Int): Step? {
        val scroll = program.getOrNull(index) ?: return null
        val next = program.getOrNull(index + 1) ?: return null
        return next.takeIf {
            scroll.kind == StepKind.SCROLL && scroll.role == null && !scroll.optional && !scroll.submit &&
                scroll.expect == listOf(Postcondition.ScrolledDown) &&
                (scroll.arg == null || scroll.arg.toIntOrNull()?.let { n -> n in 1..2000 } == true) &&
                next.kind == StepKind.CLICK && !next.optional && !next.submit &&
                next.role in setOf(Role.PAGE_NEXT, Role.LOAD_MORE) &&
                (scroll.untilRole == null || scroll.untilRole == next.role)
        }
    }

    fun target(next: Step, page: SemanticPageState, site: SiteModel, ledger: TaskLedger): Affordance? {
        if (page.pageType != PageType.RESULTS || page.isHumanOnly || page.dialogOpen || page.settle != Settle.IDLE) return null
        val ready = StepGrounder(site).ground(next, emptyMap(), page, ledger.visited, strict = true) as? GroundingOutcome.Ready ?: return null
        return ready.grounded.chosen?.affordance?.takeIf { it.role == next.role && it.sameSite && !it.isCommit }
    }

    /** Null means absent/partial/above/horizontally offscreen/unknown geometry; zero is inside. */
    fun distance(a: Affordance, page: SemanticPageState): Long? {
        val b = a.bbox ?: return null
        if (page.viewportWidth <= 0 || page.viewportHeight <= 0 || b.w <= 0 || b.h <= 0 ||
            b.x < 0 || b.x.toLong() + b.w > page.viewportWidth || b.y < 0) return null
        if (b.y.toLong() + b.h <= page.viewportHeight) return 0
        if (b.y < page.viewportHeight) return null
        return b.y.toLong() - page.viewportHeight + b.h
    }

    fun observed(program: List<Step>, index: Int, before: SemanticPageState, after: SemanticPageState,
        site: SiteModel, ledger: TaskLedger): Role? {
        val next = following(program,index) ?: return null
        if (!sameContext(before,after)) return null
        val a = target(next,before,site,ledger) ?: return null
        val b = target(next,after,site,ledger) ?: return null
        return next.role.takeIf { identity(a) == identity(b) && (distance(a,before) ?: 0) > 0 && distance(b,after) == 0L }
    }
}
