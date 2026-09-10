package dev.npn.core.xmp

import dev.npn.core.model.FieldKeys

/** Test-side builder that still names the four interop fields; production code passes one map. */
internal fun xmp(
    documentId: String? = null,
    tags: List<String> = emptyList(),
    title: String? = null,
    correspondent: String? = null,
    docType: String? = null,
    docDate: String? = null,
    reviewed: String? = null,
    fields: Map<String, String> = emptyMap(),
): NpnXmpData =
    NpnXmpData(
        documentId = documentId,
        tags = tags,
        reviewed = reviewed,
        fields =
            fields +
                listOfNotNull(
                    title?.let { FieldKeys.TITLE to it },
                    correspondent?.let { FieldKeys.CORRESPONDENT to it },
                    docType?.let { FieldKeys.DOC_TYPE to it },
                    docDate?.let { FieldKeys.DOC_DATE to it },
                ),
    )
