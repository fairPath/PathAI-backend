package com.job_search.fair_path.exceptions;

public class SavedJobLimitExceededException extends RuntimeException {
    public SavedJobLimitExceededException(int limit) {
        super("You can save up to " + limit + " jobs. Delete a saved job before adding another.");
    }
}
