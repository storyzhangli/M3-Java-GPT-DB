package com.zl.d33.document.vector.dto;

import lombok.Data;

/**
 * 向量删除结果：区分台账删除行数与 PostgreSQL 向量删除行数。
 */
@Data
public class VectorDeleteView {

    private String documentId;

    private int deletedChunkRows;

    private int deletedTaskRows;

    private int deletedVectorRows;

    private boolean documentDeleted;

    private String status;
}
