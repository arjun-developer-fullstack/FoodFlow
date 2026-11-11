/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Absolute API origin for deployments where the SPA and the gateway are on different hosts. */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
