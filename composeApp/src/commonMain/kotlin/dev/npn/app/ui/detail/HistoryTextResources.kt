package dev.npn.app.ui.detail

import androidx.compose.runtime.Composable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_history_correspondent_changed
import no_papers_needed.composeapp.generated.resources.detail_history_correspondent_cleared
import no_papers_needed.composeapp.generated.resources.detail_history_document_date_changed
import no_papers_needed.composeapp.generated.resources.detail_history_document_date_cleared
import no_papers_needed.composeapp.generated.resources.detail_history_document_deleted
import no_papers_needed.composeapp.generated.resources.detail_history_document_relinked
import no_papers_needed.composeapp.generated.resources.detail_history_document_restored
import no_papers_needed.composeapp.generated.resources.detail_history_document_trashed
import no_papers_needed.composeapp.generated.resources.detail_history_document_type_changed
import no_papers_needed.composeapp.generated.resources.detail_history_document_type_cleared
import no_papers_needed.composeapp.generated.resources.detail_history_field_removed
import no_papers_needed.composeapp.generated.resources.detail_history_field_set
import no_papers_needed.composeapp.generated.resources.detail_history_marked_needs_review
import no_papers_needed.composeapp.generated.resources.detail_history_marked_reviewed
import no_papers_needed.composeapp.generated.resources.detail_history_note_cleared
import no_papers_needed.composeapp.generated.resources.detail_history_note_updated
import no_papers_needed.composeapp.generated.resources.detail_history_ocr_engine_cleared
import no_papers_needed.composeapp.generated.resources.detail_history_ocr_engine_pinned
import no_papers_needed.composeapp.generated.resources.detail_history_review_state_changed
import no_papers_needed.composeapp.generated.resources.detail_history_review_state_cleared
import no_papers_needed.composeapp.generated.resources.detail_history_setting_changed
import no_papers_needed.composeapp.generated.resources.detail_history_tag_added
import no_papers_needed.composeapp.generated.resources.detail_history_tag_merged
import no_papers_needed.composeapp.generated.resources.detail_history_tag_removed
import no_papers_needed.composeapp.generated.resources.detail_history_tag_renamed
import no_papers_needed.composeapp.generated.resources.detail_history_tag_styled
import no_papers_needed.composeapp.generated.resources.detail_history_title_changed
import no_papers_needed.composeapp.generated.resources.detail_history_title_cleared
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
            HistoryTextKey.TITLE_CLEARED -> Res.string.detail_history_title_cleared
            HistoryTextKey.TITLE_CHANGED -> Res.string.detail_history_title_changed
            HistoryTextKey.NOTE_CLEARED -> Res.string.detail_history_note_cleared
            HistoryTextKey.NOTE_UPDATED -> Res.string.detail_history_note_updated
            HistoryTextKey.CORRESPONDENT_CLEARED -> Res.string.detail_history_correspondent_cleared
            HistoryTextKey.CORRESPONDENT_CHANGED -> Res.string.detail_history_correspondent_changed
            HistoryTextKey.DOCUMENT_TYPE_CLEARED -> Res.string.detail_history_document_type_cleared
            HistoryTextKey.DOCUMENT_TYPE_CHANGED -> Res.string.detail_history_document_type_changed
            HistoryTextKey.DOCUMENT_DATE_CLEARED -> Res.string.detail_history_document_date_cleared
            HistoryTextKey.DOCUMENT_DATE_CHANGED -> Res.string.detail_history_document_date_changed
            HistoryTextKey.MARKED_REVIEWED -> Res.string.detail_history_marked_reviewed
            HistoryTextKey.MARKED_NEEDS_REVIEW -> Res.string.detail_history_marked_needs_review
            HistoryTextKey.REVIEW_STATE_CLEARED -> Res.string.detail_history_review_state_cleared
            HistoryTextKey.REVIEW_STATE_CHANGED -> Res.string.detail_history_review_state_changed
            HistoryTextKey.OCR_ENGINE_CLEARED -> Res.string.detail_history_ocr_engine_cleared
            HistoryTextKey.OCR_ENGINE_PINNED -> Res.string.detail_history_ocr_engine_pinned
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
