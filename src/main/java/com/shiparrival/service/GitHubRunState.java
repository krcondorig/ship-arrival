package com.shiparrival.service;

/**
 * Estado de una ejecución del workflow de GitHub Actions.
 *
 * @param id         identificador numérico que asigna GitHub
 * @param status     {@code queued}, {@code in_progress} o {@code completed}
 * @param conclusion {@code success}, {@code failure}, {@code cancelled}...
 * @param htmlUrl    enlace público a la ejecución
 */
public record GitHubRunState(long id, String status, String conclusion, String htmlUrl) {

    public boolean isCompleted() {
        return "completed".equals(status);
    }

    public boolean isSuccess() {
        return isCompleted() && "success".equals(conclusion);
    }

    public boolean isFailure() {
        return isCompleted() && conclusion != null && !"success".equals(conclusion);
    }
}
