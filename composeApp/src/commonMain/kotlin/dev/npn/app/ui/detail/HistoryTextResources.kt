package dev.npn.app.ui.detail

import androidx.compose.runtime.Composable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_history_document_deleted
import no_papers_needed.composeapp.generated.resources.detail_history_document_relinked
import no_papers_needed.composeapp.generated.resources.detail_history_document_restored
import no_papers_needed.composeapp.generated.resources.detail_history_document_trashed
import no_papers_needed.composeapp.generated.resources.detail_history_field_removed
import no_papers_needed.composeapp.generated.resources.detail_history_field_set
import no_papers_needed.composeapp.generated.resources.detail_history_marked_needs_review
import no_papers_needed.composeapp.generated.resources.detail_history_marked_reviewed
import no_papers_needed.composeapp.generated.resources.detail_history_ocr_engine_cleared
import no_papers_needed.composeapp.generated.resources.detail_history_ocr_engine_pinned
import no_papers_needed.composeapp.generated.resources.detail_history_review_state_changed
import no_papers_needed.composeapp.generated.resources.detail_history_review_state_cleared
import no_papers_needed.composeapp.generated.resources.detail_history_setting_changed
import no_papers_needed.composeapp.generated.resources.detail_history_storage_path_cleared
import no_papers_needed.composeapp.generated.resources.detail_history_storage_path_set
import no_papers_needed.composeapp.generated.resources.detail_history_tag_added
import no_papers_needed.composeapp.generated.resources.detail_history_tag_merged
import no_papers_needed.composeapp.generated.resources.detail_history_tag_removed
import no_papers_needed.composeapp.generated.resources.detail_history_tag_renamed
import no_papers_needed.composeapp.generated.resources.detail_history_tag_styled
import no_papers_needed.composeapp.generated.resources.detail_history_undecodable
import no_papers_needed.composeapp.generated.resources.detail_history_unknown_event
import no_papers_needed.composeapp.generated.resources.detail_history_unknown_event_payload
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HistoryDescription.resolve(): String = stringResource(key.resource, *arguments.toTypedArray())

private val HistoryTextKey.resource: StringResource
    get() =
        when (this) {
            HistoryTextKey.UNDECODABLE -> Res.string.detail_history_undecodable
            HistoryTextKey.TAG_ADDED -> Res.string.detail_history_tag_added
            HistoryTextKey.TAG_REMOVED -> Res.string.detail_history_tag_removed
            HistoryTextKey.MARKED_REVIEWED -> Res.string.detail_history_marked_reviewed
            HistoryTextKey.MARKED_NEEDS_REVIEW -> Res.string.detail_history_marked_needs_review
            HistoryTextKey.REVIEW_STATE_CLEARED -> Res.string.detail_history_review_state_cleared
            HistoryTextKey.REVIEW_STATE_CHANGED -> Res.string.detail_history_review_state_changed
            HistoryTextKey.OCR_ENGINE_CLEARED -> Res.string.detail_history_ocr_engine_cleared
            HistoryTextKey.OCR_ENGINE_PINNED -> Res.string.detail_history_ocr_engine_pinned
            HistoryTextKey.STORAGE_PATH_SET -> Res.string.detail_history_storage_path_set
            HistoryTextKey.STORAGE_PATH_CLEARED -> Res.string.detail_history_storage_path_cleared
            HistoryTextKey.FIELD_REMOVED -> Res.string.detail_history_field_removed
            HistoryTextKey.FIELD_SET -> Res.string.detail_history_field_set
            HistoryTextKey.DOCUMENT_TRASHED -> Res.string.detail_history_document_trashed
            HistoryTextKey.DOCUMENT_RESTORED -> Res.string.detail_history_document_restored
            HistoryTextKey.DOCUMENT_DELETED -> Res.string.detail_history_document_deleted
            HistoryTextKey.DOCUMENT_RELINKED -> Res.string.detail_history_document_relinked
            HistoryTextKey.TAG_RENAMED -> Res.string.detail_history_tag_renamed
            HistoryTextKey.TAG_MERGED -> Res.string.detail_history_tag_merged
            HistoryTextKey.TAG_STYLED -> Res.string.detail_history_tag_styled
            HistoryTextKey.SETTING_CHANGED -> Res.string.detail_history_setting_changed
            HistoryTextKey.UNKNOWN_EVENT -> Res.string.detail_history_unknown_event
            HistoryTextKey.UNKNOWN_EVENT_WITH_PAYLOAD -> Res.string.detail_history_unknown_event_payload
        }
