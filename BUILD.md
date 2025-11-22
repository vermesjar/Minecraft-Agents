# Build Instructions

This project uses Gradle for building.

## Prerequisites

- Java Development Kit (JDK) 17
- Windows environment (for `.bat` scripts)

## Building the Mod

1. Open a terminal in the project root directory.
2. Run the following command:
   ```powershell
   .\gradlew.bat build
   ```
3. The build process will download dependencies and compile the code.
4. Upon success, the mod JAR file will be located in:
   `build/libs/steve-ai-mod-1.0.0.jar` (version number may vary)

## Troubleshooting

- If you encounter permission errors, ensure `gradlew.bat` is executable.
- If you have Java version issues, ensure `JAVA_HOME` is set to a JDK 17 installation.
