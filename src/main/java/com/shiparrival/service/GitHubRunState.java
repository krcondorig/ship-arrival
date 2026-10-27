package com.shiparrival.service;

/**
 * Estado de una ejecución del workflow de GitHub Actions.
 *
 * @param id             identificador numérico que asigna GitHub
 * @param status         {@code queued}, {@code waiting}, {@code in_progress} o {@code completed}
 * @param conclusion     {@code success}, {@code failure}, {@code cancelled}...
 * @param htmlUrl        enlace público a la ejecución
 * @param failedStepName primer paso que falló, si lo hubo
 */
public record GitHubRunState(long id, String status, String conclusion, String htmlUrl,
                             String failedStepName) {

    public boolean isCompleted() {
        return "completed".equals(status);
    }

    public boolean isSuccess() {
        return isCompleted() && "success".equals(conclusion);
    }

    public boolean isFailure() {
        return isCompleted() && conclusion != null && !"success".equals(conclusion);
    }

    public boolean isQueued() {
        return "queued".equals(status) || "waiting".equals(status);
    }

    public boolean isInProgress() {
        return "in_progress".equals(status);
    }
}
