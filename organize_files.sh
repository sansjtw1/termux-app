#!/bin/bash
# Script to organize Java files into correct directory structure

cd /workspace/app/src/main/java/com/kalinrx

# Move files to their correct locations based on their package declarations
for file in *.java; do
    if [ -f "$file" ]; then
        package=$(head -n 1 "$file" | sed 's/package //g' | sed 's/;//g')
        
        case "$package" in
            "com.kalinrx.app.activities")
                mv "$file" app/activities/
                ;;
            "com.kalinrx.app.api.file")
                mv "$file" app/api/file/
                ;;
            "com.kalinrx.app.event")
                mv "$file" app/event/
                ;;
            "com.kalinrx.app.terminal")
                mv "$file" app/terminal/
                ;;
            "com.kalinrx.app.fragments.settings")
                mv "$file" app/fragments/settings/
                ;;
            "com.kalinrx.app.fragments.settings.termux")
                mv "$file" app/fragments/settings/termux/
                ;;
            "com.kalinrx.app.fragments.settings.termux_api")
                mv "$file" app/fragments/settings/termux_api/
                ;;
            "com.kalinrx.app.fragments.settings.termux_boot")
                mv "$file" app/fragments/settings/termux_boot/
                ;;
            "com.kalinrx.app.fragments.settings.termux_float")
                mv "$file" app/fragments/settings/termux_float/
                ;;
            "com.kalinrx.app.fragments.settings.termux_tasker")
                mv "$file" app/fragments/settings/termux_tasker/
                ;;
            "com.kalinrx.app.fragments.settings.termux_widget")
                mv "$file" app/fragments/settings/termux_widget/
                ;;
            "com.kalinrx.app")
                mv "$file" app/
                ;;
            "com.kalinrx.filepicker")
                mv "$file" filepicker/
                ;;
            "com.kalinrx.shared")
                mv "$file" shared/
                ;;
        esac
    fi
done
