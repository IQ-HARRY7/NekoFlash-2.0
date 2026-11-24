package ru.forum.adbfastboottool

/** Wire-order targets for a queue snapshot. An unresolved source still occupies one visible row. */
internal object MassFlashPlan {
    data class Step(val source: FlashQueueDraftItem, val target: String?)

    fun resolve(
        draft: FlashOperationDraft,
        resolveSlot: (String, String) -> List<String>?
    ): List<Step> = draft.items.flatMap { source ->
        val targets = FlashOperationDraftPolicy.resolveTargets(source.partition, draft.slot, resolveSlot)
        targets?.map { Step(source, it) } ?: listOf(Step(source, null))
    }

    fun repeatedTargets(steps: List<Step>): Set<String> = steps.mapNotNull(Step::target)
        .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}
