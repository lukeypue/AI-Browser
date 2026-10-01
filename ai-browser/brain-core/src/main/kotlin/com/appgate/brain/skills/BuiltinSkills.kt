package com.appgate.brain.skills

import com.appgate.brain.model.PageType
import com.appgate.brain.model.Postcondition
import com.appgate.brain.model.Precondition
import com.appgate.brain.model.Role
import com.appgate.brain.model.Skill
import com.appgate.brain.model.SkillOrigin
import com.appgate.brain.model.Step
import com.appgate.brain.model.StepKind

/**
 * The site-agnostic skill library the brain ships with. Every skill is a program over
 * affordance roles and canonical facet keys; `Opt` steps let one skill serve a site with
 * inline facets and a site with a filter drawer without branching code.
 */
object BuiltinSkills {
    private val browsePages = setOf(PageType.HOME, PageType.SEARCH, PageType.RESULTS, PageType.UNKNOWN, PageType.FACET_PANEL, PageType.DETAIL, PageType.PROFILE)
    private val resultPages = setOf(PageType.RESULTS, PageType.SEARCH, PageType.FACET_PANEL)

    val search = Skill(
        id = "search", version = 3, intent = "type a query into the site search and submit",
        params = listOf("query"),
        pre = listOf(Precondition.PageTypeIn(browsePages), Precondition.HasRole(Role.SEARCH_BOX)),
        body = listOf(
            Step(StepKind.DISMISS, Role.CLOSE, optional = true, expect = listOf(Postcondition.DialogClosed)),
            Step(StepKind.TYPE, Role.SEARCH_BOX, arg = "\$query", submit = true,
                expect = listOf(Postcondition.anyOf(Postcondition.PageTypeIs(PageType.RESULTS), Postcondition.ResultsChanged, Postcondition.UrlQueryHas("q"), Postcondition.UrlChanged))),
            Step(StepKind.CLICK, Role.SUBMIT, optional = true,
                expect = listOf(Postcondition.anyOf(Postcondition.PageTypeIs(PageType.RESULTS), Postcondition.ResultsChanged)))
        ),
        post = listOf(Postcondition.anyOf(Postcondition.PageTypeIs(PageType.RESULTS), Postcondition.ResultsChanged)),
        origin = SkillOrigin.BUILTIN, tags = setOf("search", "navigate")
    )

    val constrainNumeric = Skill(
        id = "constrain_numeric", version = 4, intent = "apply an upper or lower bound on a numeric facet",
        params = listOf("key", "value"),
        pre = listOf(Precondition.PageTypeIn(resultPages)),
        body = listOf(
            Step(StepKind.CLICK, Role.FACET_OPEN, facetKey = "\$key", optional = true,
                expect = listOf(Postcondition.anyOf(Postcondition.DialogOpened, Postcondition.RoleAppeared(Role.FACET), Postcondition.PageTypeIs(PageType.FACET_PANEL)))),
            Step(StepKind.SET_RANGE, Role.FACET, facetKey = "\$key", arg = "\$value", submit = true,
                expect = listOf(Postcondition.anyOf(Postcondition.ConstraintApplied("\$key", "\$value"), Postcondition.ValueIs("\$key", "\$value"), Postcondition.ResultsChanged))),
            // A pending value inside an open sheet is not an applied constraint: the optional Apply
            // step must not be skipped as "already holding" while the drawer is still open.
            Step(StepKind.CLICK, Role.FACET_APPLY, optional = true,
                expect = listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.DialogClosed)))
        ),
        post = listOf(Postcondition.anyOf(Postcondition.ConstraintApplied("\$key", "\$value"), Postcondition.ValueIs("\$key", "\$value"))),
        origin = SkillOrigin.BUILTIN, tags = setOf("filter", "numeric")
    )

    val selectFacet = Skill(
        id = "select_facet", version = 4, intent = "choose a value for a categorical facet (make, model, condition...)",
        params = listOf("key", "value"),
        pre = listOf(Precondition.PageTypeIn(resultPages)),
        body = listOf(
            Step(StepKind.CLICK, Role.FACET_OPEN, facetKey = "\$key", optional = true,
                expect = listOf(Postcondition.anyOf(Postcondition.DialogOpened, Postcondition.RoleAppeared(Role.FACET), Postcondition.PageTypeIs(PageType.FACET_PANEL)))),
            Step(StepKind.SELECT, Role.FACET, facetKey = "\$key", arg = "\$value",
                expect = listOf(Postcondition.anyOf(Postcondition.ConstraintApplied("\$key", "\$value"), Postcondition.ValueIs("\$key", "\$value"), Postcondition.ResultsChanged))),
            // A pending value inside an open sheet is not an applied constraint: the optional Apply
            // step must not be skipped as "already holding" while the drawer is still open.
            Step(StepKind.CLICK, Role.FACET_APPLY, optional = true,
                expect = listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.DialogClosed)))
        ),
        post = listOf(Postcondition.anyOf(Postcondition.ConstraintApplied("\$key", "\$value"), Postcondition.ValueIs("\$key", "\$value"))),
        origin = SkillOrigin.BUILTIN, tags = setOf("filter", "choice")
    )

    val openFilters = Skill(
        id = "open_filters", version = 2, intent = "open the filter panel or drawer",
        params = emptyList(),
        pre = listOf(Precondition.PageTypeIn(resultPages), Precondition.HasRole(Role.FACET_OPEN)),
        body = listOf(Step(StepKind.CLICK, Role.FACET_OPEN,
            expect = listOf(Postcondition.anyOf(Postcondition.DialogOpened, Postcondition.RoleAppeared(Role.FACET), Postcondition.PageTypeIs(PageType.FACET_PANEL), Postcondition.RoleAppeared(Role.FACET_APPLY))))),
        post = listOf(Postcondition.anyOf(Postcondition.DialogOpened, Postcondition.RoleAppeared(Role.FACET))),
        origin = SkillOrigin.BUILTIN, tags = setOf("filter")
    )

    /** Expand one named facet inside an already open filter sheet (an accordion such as "Make"). */
    val openFacet = Skill(
        id = "open_facet", version = 1, intent = "expand a named facet section inside the filter panel",
        params = listOf("key"),
        pre = listOf(Precondition.PageTypeIn(resultPages), Precondition.HasRole(Role.FACET_OPEN)),
        body = listOf(Step(StepKind.CLICK, Role.FACET_OPEN, facetKey = "\$key",
            expect = listOf(Postcondition.RoleAppeared(Role.FACET, "\$key")))),
        post = listOf(Postcondition.RoleAppeared(Role.FACET, "\$key")),
        origin = SkillOrigin.BUILTIN, tags = setOf("filter")
    )

    val applyFilters = Skill(
        id = "apply_filters", version = 2, intent = "confirm pending filter choices",
        params = emptyList(),
        pre = listOf(Precondition.HasRole(Role.FACET_APPLY)),
        body = listOf(Step(StepKind.CLICK, Role.FACET_APPLY,
            expect = listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.DialogClosed, Postcondition.PageTypeIs(PageType.RESULTS))))),
        post = listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.DialogClosed)),
        origin = SkillOrigin.BUILTIN, tags = setOf("filter")
    )

    val sortResults = Skill(
        id = "sort_results", version = 2, intent = "change the result ordering",
        params = listOf("order"),
        pre = listOf(Precondition.PageTypeIn(resultPages), Precondition.HasRole(Role.SORT)),
        body = listOf(Step(StepKind.SELECT, Role.SORT, arg = "\$order",
            expect = listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.UrlQueryHas("sort"), Postcondition.DialogOpened)))),
        post = listOf(Postcondition.anyOf(Postcondition.ResultsChanged, Postcondition.UrlQueryHas("sort"))),
        origin = SkillOrigin.BUILTIN, tags = setOf("sort")
    )

    val nextPage = Skill(
        id = "next_page", version = 2, intent = "go to the next page of results",
        params = emptyList(),
        pre = listOf(Precondition.PageTypeIn(setOf(PageType.RESULTS)), Precondition.HasRole(Role.PAGE_NEXT)),
        body = listOf(Step(StepKind.CLICK, Role.PAGE_NEXT, expect = listOf(Postcondition.anyOf(Postcondition.NewResults, Postcondition.ResultsChanged)))),
        post = listOf(Postcondition.anyOf(Postcondition.NewResults, Postcondition.ResultsChanged)),
        origin = SkillOrigin.BUILTIN, tags = setOf("paginate")
    )

    val loadMore = Skill(
        id = "load_more", version = 2, intent = "load more results via a load-more control",
        params = emptyList(),
        pre = listOf(Precondition.PageTypeIn(setOf(PageType.RESULTS)), Precondition.HasRole(Role.LOAD_MORE)),
        body = listOf(Step(StepKind.CLICK, Role.LOAD_MORE, expect = listOf(Postcondition.anyOf(Postcondition.NewResults, Postcondition.EndOfResults)))),
        post = listOf(Postcondition.anyOf(Postcondition.NewResults, Postcondition.EndOfResults)),
        origin = SkillOrigin.BUILTIN, tags = setOf("paginate")
    )

    val scrollResults = Skill(
        id = "scroll_results", version = 2, intent = "scroll to reveal more results (infinite scroll)",
        params = emptyList(),
        pre = listOf(Precondition.PageTypeIn(setOf(PageType.RESULTS))),
        body = listOf(Step(StepKind.SCROLL, expect = listOf(Postcondition.anyOf(Postcondition.NewResults, Postcondition.EndOfResults, Postcondition.ScrolledDown)))),
        post = listOf(Postcondition.anyOf(Postcondition.NewResults, Postcondition.EndOfResults)),
        origin = SkillOrigin.BUILTIN, tags = setOf("paginate")
    )

    val openItem = Skill(
        id = "open_item", version = 2, intent = "open a result item to its detail page",
        params = listOf("item"),
        pre = listOf(Precondition.PageTypeIn(setOf(PageType.RESULTS)), Precondition.HasRole(Role.RESULT_ITEM)),
        body = listOf(Step(StepKind.CLICK, Role.RESULT_ITEM, arg = "\$item", expect = listOf(Postcondition.DetailMatches(null)))),
        post = listOf(Postcondition.PageTypeIs(PageType.DETAIL)),
        origin = SkillOrigin.BUILTIN, tags = setOf("detail")
    )

    val expandDescription = Skill(
        id = "expand_description", version = 2, intent = "expand a truncated description on a detail page",
        params = emptyList(),
        pre = listOf(Precondition.PageTypeIn(setOf(PageType.DETAIL)), Precondition.HasRole(Role.EXPAND_TEXT)),
        body = listOf(Step(StepKind.CLICK, Role.EXPAND_TEXT, expect = listOf(Postcondition.TextExpanded))),
        post = listOf(Postcondition.TextExpanded),
        origin = SkillOrigin.BUILTIN, tags = setOf("detail")
    )

    val goBack = Skill(
        id = "go_back", version = 2, intent = "return to the previous page",
        params = emptyList(),
        pre = emptyList(),
        body = listOf(Step(StepKind.BACK, expect = listOf(Postcondition.UrlChanged))),
        post = listOf(Postcondition.UrlChanged),
        origin = SkillOrigin.BUILTIN, tags = setOf("navigate")
    )

    val dismissDialog = Skill(
        id = "dismiss_dialog", version = 2, intent = "close a blocking dialog or overlay",
        params = emptyList(),
        pre = listOf(Precondition.HasRole(Role.CLOSE)),
        body = listOf(Step(StepKind.DISMISS, Role.CLOSE, expect = listOf(Postcondition.DialogClosed))),
        post = listOf(Postcondition.DialogClosed),
        origin = SkillOrigin.BUILTIN, tags = setOf("dialog")
    )

    val prepareMessage = Skill(
        id = "prepare_message", version = 2, intent = "open the seller composer and fill a message WITHOUT sending",
        params = listOf("text"),
        pre = listOf(Precondition.PageTypeIn(setOf(PageType.DETAIL, PageType.MESSAGES, PageType.DIALOG))),
        body = listOf(
            Step(StepKind.CLICK, Role.MESSAGE_SELLER, optional = true, expect = listOf(Postcondition.anyOf(Postcondition.ComposerReady, Postcondition.DialogOpened, Postcondition.PageTypeIs(PageType.MESSAGES)))),
            Step(StepKind.TYPE, Role.COMPOSER_INPUT, arg = "\$text", submit = false, expect = listOf(Postcondition.ComposerReady))
        ),
        post = listOf(Postcondition.ComposerReady),
        origin = SkillOrigin.BUILTIN, tags = setOf("message", "prepare")
    )

    /** COMMIT step: the executor refuses it without a matching, unexpired, unused grant. */
    val commitSend = Skill(
        id = "commit_send", version = 2, intent = "press Send on a previewed message (grant required)",
        params = emptyList(),
        pre = listOf(Precondition.HasRole(Role.SEND), Precondition.HasRole(Role.COMPOSER_INPUT)),
        body = listOf(Step(StepKind.CLICK, Role.SEND, expect = listOf(Postcondition.anyOf(Postcondition.DialogClosed, Postcondition.PageTypeIs(PageType.MESSAGES), Postcondition.UrlChanged)))),
        post = listOf(Postcondition.anyOf(Postcondition.DialogClosed, Postcondition.PageTypeIs(PageType.MESSAGES))),
        origin = SkillOrigin.BUILTIN, tags = setOf("message", "commit")
    )

    val openCategory = Skill(
        id = "open_category", version = 2, intent = "navigate into a category or section link",
        params = listOf("name"),
        pre = listOf(Precondition.HasRole(Role.CATEGORY_LINK)),
        body = listOf(Step(StepKind.CLICK, Role.CATEGORY_LINK, arg = "\$name", expect = listOf(Postcondition.UrlChanged))),
        post = listOf(Postcondition.UrlChanged),
        origin = SkillOrigin.BUILTIN, tags = setOf("navigate")
    )

    val all: List<Skill> = listOf(
        search, constrainNumeric, selectFacet, openFilters, openFacet, applyFilters, sortResults, nextPage, loadMore,
        scrollResults, openItem, expandDescription, goBack, dismissDialog, prepareMessage, commitSend, openCategory
    )

    fun byId(id: String): Skill? = all.firstOrNull { it.id == id }
}
