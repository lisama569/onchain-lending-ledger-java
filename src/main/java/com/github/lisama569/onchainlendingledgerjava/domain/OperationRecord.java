package com.github.lisama569.onchainlendingledgerjava.domain;

/**
 * 幂等记录：一个 requestId 的处理结果，随快照持久化，重启后仍可识别重复提交。
 */
public class OperationRecord {

    private String requestId;
    /** 请求操作类型，如 SUPPLY / BORROW，用于检测同一 requestId 被复用于不同操作。 */
    private String operation;
    private boolean success;
    private String errorCode;
    private String message;
    private long createdAt;
    private long completedAt;

    public OperationRecord() {
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }
}
