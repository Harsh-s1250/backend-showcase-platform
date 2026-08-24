package com.example.platform.analyzer;

/**
 * The concrete UI experience selected for a project, per PRD §31 "Backend Changes".
 * A ProjectType alone isn't enough to pick a UI — a REST_APPLICATION might still fall
 * back to API_EXPLORER if a reliable generated UI can't be produced (PRD §21, Feature 9).
 */
public enum InterfaceType {
    GENERATED_REST_UI,
    API_EXPLORER,
    BROWSER_TERMINAL,
    NONE
}
