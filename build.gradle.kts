// Root build: modules configure themselves through build-logic convention plugins.
// selfwatch.root adds repo-wide checks (forbiddenNetworkApi) to `check`.
plugins { id("selfwatch.root") }
