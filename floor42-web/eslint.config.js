// Lint for the quality suite (docs/quality.md): the recommended rules only, reported to SonarQube, nothing gates a build.
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'

export default tseslint.config(
  { ignores: ['dist/**', 'dist-demo/**', 'e2e-results/**', 'node_modules/**', 'target/**'] },
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
  },
)
