# VereinsDeckel

**VereinsDeckel** is a modern Point of Sale (POS) system designed specifically for associations (Vereine). It simplifies the management of sales, members, and products, with integrated support for card payments via SumUp.

## Features

- **Sales Management**: Easy-to-use interface for recording sales.
- **Member Management**: Track member balances and categories.
- **Product Management**: Manage products, variants, and categories.
- **SumUp Integration**: Accept card payments directly through the app.
- **Analytics & History**: Detailed sales reports and transaction history.
- **Automated Backups**: Secure your data with automated backup functionality.

## Tech Stack

- **UI**: Jetpack Compose
- **Database**: Room (SQLite)
- **Asynchronous Work**: Kotlin Coroutines & Flow
- **Background Tasks**: WorkManager
- **Payment SDK**: SumUp Merchant SDK
- **Architecture**: MVVM

## Getting Started

### Prerequisites

- Android Studio Koala or newer.
- Android SDK 26+.
- A SumUp Affiliate Key (for payment functionality).

### Setup

1. Clone the repository:
   ```bash
   git clone git@github.com:JonasPrenn/POS-System.git
   ```
2. Open the project in Android Studio.
3. Sync Gradle and build the project.
4. Add your SumUp Affiliate Key in the app settings to enable card payments.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
