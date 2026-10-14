package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EnrichmentBlockSpec as EnrichmentBlockSpecDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EnrichmentCallResult as EnrichmentCallResultDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EnrichmentDiagnosis as EnrichmentDiagnosisDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EnrichmentDiagnosisAttempt as EnrichmentDiagnosisAttemptDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EnrichmentFailureSummary as EnrichmentFailureSummaryDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EnrichmentReport as EnrichmentReportDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ModelState as ModelStateDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ResolvedBlock as ResolvedBlockDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ResolvedEnrichmentBlock as ResolvedEnrichmentBlockDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookMatchingEventPage
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookPreviewItem as WebhookPreviewItemDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookPreviewResult as WebhookPreviewResultDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookResendAccepted as WebhookResendAcceptedDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookResendRange as WebhookResendRangeDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookResendResult as WebhookResendResultDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookResendSkipped as WebhookResendSkippedDto
import br.com.bbchain.evex.indexer.application.block.ResolvedBlock
import br.com.bbchain.evex.indexer.application.webhook.MatchingEventsPage
import br.com.bbchain.evex.indexer.application.webhook.PreviewEnrichment
import br.com.bbchain.evex.indexer.application.webhook.PreviewEnrichmentBlock
import br.com.bbchain.evex.indexer.application.webhook.PreviewItem
import br.com.bbchain.evex.indexer.application.webhook.PreviewModelState
import br.com.bbchain.evex.indexer.application.webhook.ResendRange
import br.com.bbchain.evex.indexer.application.webhook.WebhookPreviewResult
import br.com.bbchain.evex.indexer.application.webhook.WebhookResendOutcome
import br.com.bbchain.evex.indexer.domain.enrichment.DiagnosisAttempt
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockSpec
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCallReport
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentDiagnosis
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentFailureAggregate
import br.com.bbchain.evex.indexer.domain.blockchain.ProbeResult
import java.time.ZoneOffset

// Mapeamentos DTO gerado <-> domínio/application dos endpoints da E33 (preview, matching-events, blocks).
// Só conversão de formato — nenhuma regra de negócio.

internal fun EnrichmentBlockSpecDto.toDomain(): EnrichmentBlockSpec = EnrichmentBlockSpec(
    mode = mode ?: EnrichmentBlockSpec.EVENT_BLOCK,
    blockNumber = blockNumber,
    blockDate = blockDate?.toInstant(),
)

internal fun EnrichmentCallReport.toDto(): EnrichmentCallResultDto = EnrichmentCallResultDto(
    profileId = profileId,
    targetContractAddress = targetContractAddress,
    methodName = methodName,
    status = status,
    outputs = outputs?.toMutableMap(),
    code = code,
    message = message,
    rpcCode = rpcCode,
    rpcData = rpcData,
    classification = classification,
    blockUsed = blockUsed,
)

private fun EnrichmentFailureAggregate.toDto() = EnrichmentFailureSummaryDto(
    contract = contract,
    method = method,
    count = count,
    code = code,
    block = block,
    rpcCode = rpcCode,
    message = message,
)

private fun DiagnosisAttempt.toDto() = EnrichmentDiagnosisAttemptDto(
    block = EnrichmentCallReport.blockLabel(block) ?: "unknown",
    ok = ok,
    code = failure?.code,
    message = failure?.message,
    rpcCode = failure?.rpcCode,
)

private fun ProbeResult.toLabel(): String = when (this) {
    is ProbeResult.Ok -> "PROBE_STATE_OK"
    is ProbeResult.Failed -> "PROBE_STATE_UNAVAILABLE"
}

private fun EnrichmentDiagnosis.toDto() = EnrichmentDiagnosisDto(
    targetContractAddress = outcome.call.targetContractAddress,
    methodName = outcome.call.methodName,
    verdict = verdict,
    profileId = outcome.profileId,
    atLatest = atLatest?.toDto(),
    atEventBlock = atEventBlock?.toDto(),
    probe = probe?.toLabel(),
)

private fun PreviewEnrichment.toDto() = EnrichmentReportDto(
    calls = calls.map { it.toDto() }.toMutableList(),
    unresolvedTokens = unresolvedTokens.toMutableList(),
    blockUsed = blockUsed,
    failuresSummary = failuresSummary.map { it.toDto() }.toMutableList(),
    diagnoses = diagnoses?.map { it.toDto() }?.toMutableList(),
)

private fun PreviewModelState.toDto() = ModelStateDto(
    newerThanEvent = newerThanEvent,
    stateAt = stateAt?.atOffset(ZoneOffset.UTC),
)

private fun PreviewItem.toDto() = WebhookPreviewItemDto(
    eventId = eventId,
    matches = matches,
    notMatchingReason = notMatchingReason,
    payload = payload,
    modelState = modelState?.toDto(),
    enrichment = enrichment?.toDto(),
    warnings = warnings.toMutableList(),
)

private fun PreviewEnrichmentBlock.toDto() = ResolvedEnrichmentBlockDto(
    mode = mode,
    blockNumber = blockNumber,
    resolvedFromDate = resolvedFromDate?.atOffset(ZoneOffset.UTC),
    source = source,
    approximate = approximate,
    warnings = warnings.toMutableList(),
)

internal fun WebhookPreviewResult.toDto() = WebhookPreviewResultDto(
    items = items.map { it.toDto() }.toMutableList(),
    enrichmentBlock = enrichmentBlock.toDto(),
    failuresSummary = failuresSummary.map { it.toDto() }.toMutableList(),
    warnings = warnings.toMutableList(),
)

internal fun MatchingEventsPage.toDto() = WebhookMatchingEventPage(
    content = events.map { it.toEventDto() }.toMutableList(),
    scanned = scanned,
    scanTruncated = scanTruncated,
    nextCursor = nextCursor,
    filtersApplied = filtersApplied,
    warnings = warnings.toMutableList(),
)

internal fun ResolvedBlock.toDto() = ResolvedBlockDto(
    source = source,
    approximate = approximate,
    blockNumber = blockNumber,
    blockTimestamp = blockTimestamp?.atOffset(ZoneOffset.UTC),
    warnings = warnings.toMutableList(),
)

internal fun WebhookResendRangeDto.toDomain() = ResendRange(
    fromBlock = fromBlock,
    toBlock = toBlock,
    fromDate = fromDate?.toInstant(),
    toDate = toDate?.toInstant(),
)

internal fun WebhookResendOutcome.toDto() = WebhookResendResultDto(
    accepted = accepted.map { WebhookResendAcceptedDto(eventId = it.eventId, deliveryId = it.deliveryId) }.toMutableList(),
    skipped = skipped.map { WebhookResendSkippedDto(eventId = it.eventId, reason = it.reason) }.toMutableList(),
    modelState = modelState.toDto(),
    enrichmentBlock = enrichmentBlock.toDto(),
    warnings = warnings.toMutableList(),
)
