package com.zl.d33.document.vector.support;

import com.zl.d33.document.vector.dto.VectorChunkView;
import com.zl.d33.document.vector.dto.VectorIndexTaskView;
import com.zl.d33.document.vector.model.VectorChunkRecord;
import com.zl.d33.document.vector.model.VectorChunkStatus;
import com.zl.d33.document.vector.model.VectorIndexStage;
import com.zl.d33.document.vector.model.VectorIndexStatus;
import com.zl.d33.document.vector.model.VectorIndexTask;
import com.zl.d33.document.vector.po.VectorChunkIndexPO;
import com.zl.d33.document.vector.po.VectorIndexTaskPO;
import org.springframework.stereotype.Component;

/**
 * 向量任务/Chunk 台账的 PO、领域模型与视图转换。
 */
@Component
public class VectorIndexConverter {

    public VectorIndexTask toDomain(VectorIndexTaskPO po) {
        if (po == null) {
            return null;
        }
        VectorIndexTask task = new VectorIndexTask();
        task.setId(po.getId());
        task.setDocumentId(po.getDocumentId());
        task.setVersion(po.getVersion());
        task.setStrategy(po.getStrategy());
        task.setContentHash(po.getContentHash());
        task.setSplitVersion(po.getSplitVersion());
        task.setVectorSpaceId(po.getVectorSpaceId());
        task.setChunkCount(po.getChunkCount() == null ? 0 : po.getChunkCount());
        task.setStatus(VectorIndexStatus.fromName(po.getStatus()));
        task.setStage(po.getStage() == null || po.getStage().isBlank()
                ? null : VectorIndexStage.valueOf(po.getStage()));
        task.setErrorCode(po.getErrorCode());
        task.setErrorMessage(po.getErrorMessage());
        task.setAttemptCount(po.getAttemptCount() == null ? 0 : po.getAttemptCount());
        task.setNextRetryAt(po.getNextRetryAt());
        task.setCreatedAt(po.getCreatedAt());
        task.setUpdatedAt(po.getUpdatedAt());
        return task;
    }

    public VectorChunkRecord toDomain(VectorChunkIndexPO po) {
        if (po == null) {
            return null;
        }
        VectorChunkRecord chunk = new VectorChunkRecord();
        chunk.setId(po.getId());
        chunk.setDocumentId(po.getDocumentId());
        chunk.setVersion(po.getVersion());
        chunk.setStrategy(po.getStrategy());
        chunk.setChunkId(po.getChunkId());
        chunk.setChunkIndex(po.getChunkIndex() == null ? 0 : po.getChunkIndex());
        chunk.setContent(po.getContent());
        chunk.setChunkContentHash(po.getChunkContentHash());
        chunk.setCharacterCount(po.getCharacterCount() == null ? 0 : po.getCharacterCount());
        chunk.setPageNumbers(po.getPageNumbers());
        chunk.setStatus(VectorChunkStatus.fromName(po.getStatus()));
        chunk.setCreatedAt(po.getCreatedAt());
        chunk.setUpdatedAt(po.getUpdatedAt());
        return chunk;
    }

    public VectorIndexTaskView toView(VectorIndexTask task) {
        VectorIndexTaskView view = new VectorIndexTaskView();
        view.setDocumentId(task.getDocumentId());
        view.setVersion(task.getVersion());
        view.setStrategy(task.getStrategy());
        view.setStatus(task.getStatus() == null ? null : task.getStatus().name());
        view.setStage(task.getStage() == null ? null : task.getStage().name());
        view.setChunkCount(task.getChunkCount());
        view.setAttemptCount(task.getAttemptCount());
        view.setErrorCode(task.getErrorCode());
        view.setErrorMessage(task.getErrorMessage());
        view.setSplitVersion(task.getSplitVersion());
        view.setVectorSpaceId(task.getVectorSpaceId());
        view.setNextRetryAt(task.getNextRetryAt());
        view.setCreatedAt(task.getCreatedAt());
        view.setUpdatedAt(task.getUpdatedAt());
        return view;
    }

    public VectorChunkView toView(VectorChunkRecord chunk) {
        VectorChunkView view = new VectorChunkView();
        view.setDocumentId(chunk.getDocumentId());
        view.setVersion(chunk.getVersion());
        view.setStrategy(chunk.getStrategy());
        view.setChunkId(chunk.getChunkId());
        view.setChunkIndex(chunk.getChunkIndex());
        view.setChunkContentHash(chunk.getChunkContentHash());
        view.setCharacterCount(chunk.getCharacterCount());
        view.setPageNumbers(chunk.getPageNumbers());
        view.setStatus(chunk.getStatus() == null ? null : chunk.getStatus().name());
        view.setContentPreview(preview(chunk.getContent()));
        return view;
    }

    private String preview(String content) {
        if (content == null) {
            return "";
        }
        String oneLine = content.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "...";
    }
}
