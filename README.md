# Puffin
**Puffin** is BlueDragon's container orchestration service and queue system.
It runs in a Docker container with access to the external Docker runtime to create new containers.

## Usage
- Clone: `git clone https://github.com/BlueDragonMC/Puffin.git`
- Build: `./gradlew build`
- To provide maps: Create a `worlds` folder in the root of the project. (subject to change)
- To use GitHub API features: Create a `puffin.properties` file in the current working directory with the contents:
```properties
# GitHub personal access tokens can be created in your 
# GitHub account settings, under "developer settings"
gh_token=<your GitHub personal access token>
```