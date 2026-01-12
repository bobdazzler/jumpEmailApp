# AI Email Sorting App

An intelligent email sorting application built with Spring Boot that uses AI to automatically categorize emails from Gmail into user-defined categories.

## Features

- **OAuth2 Google Sign-In**: Secure authentication with Google
- **AI-Powered Email Classification**: Automatically sorts emails into custom categories using AI
- **AI Email Summarization**: Generates concise summaries for each email
- **Multiple Gmail Account Support**: Connect and manage multiple Gmail accounts
- **Bulk Actions**: Delete or unsubscribe from multiple emails at once
- **Automatic Archiving**: Emails are automatically archived in Gmail after sorting
- **Real-time Processing**: Scheduled tasks check for new emails every minute

## Tech Stack

- **Backend**: Spring Boot 3.2.5
- **Security**: Spring Security with OAuth2
- **Database**: PostgreSQL
- **AI**: OpenAI GPT-3.5-turbo
- **Frontend**: Thymeleaf + HTMX + Alpine.js
- **Deployment**: Fly.io

## Prerequisites

- Java 21
- PostgreSQL database
- Google Cloud Platform project with OAuth2 credentials
- OpenAI API key
- Maven 3.6+

## Setup Instructions

### 1. Google Cloud Platform Setup

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select an existing one
3. Enable the Gmail API
4. Go to "Credentials" → "Create Credentials" → "OAuth 2.0 Client ID"
5. Configure OAuth consent screen:
   - Choose "External" (for testing)
   - **IMPORTANT**: Add test users - you MUST add `webshookeng@gmail.com` as a test user in the OAuth consent screen
   - Add scopes: 
     - `openid`
     - `profile`
     - `email`
     - `https://www.googleapis.com/auth/gmail.readonly`
     - `https://www.googleapis.com/auth/gmail.modify`
   - Note: Since this app requests Gmail scopes, it requires a security review for production use. For development, you can add test users in the OAuth consent screen.
6. Create OAuth 2.0 Client ID:
   - Application type: Web application
   - Authorized redirect URIs: `http://localhost:8080/login/oauth2/code/google` (for local dev)
   - Copy the Client ID and Client Secret

### 2. Database Setup

Create a PostgreSQL database:

```sql
CREATE DATABASE jump;
```

Or use environment variables for connection details.

### 3. Configuration

Update `src/main/resources/application.properties` with your credentials:

```properties
# Google OAuth2
spring.security.oauth2.client.registration.google.client-id=YOUR_CLIENT_ID
spring.security.oauth2.client.registration.google.client-secret=YOUR_CLIENT_SECRET

# OpenAI
openai.api.key=YOUR_OPENAI_API_KEY

# Database (or use environment variables)
spring.datasource.url=jdbc:postgresql://localhost:5432/jump
spring.datasource.username=postgres
spring.datasource.password=your_password
```

Or use environment variables:

```bash
export GOOGLE_CLIENT_ID=your_client_id
export GOOGLE_CLIENT_SECRET=your_client_secret
export OPENAI_API_KEY=your_openai_key
export DATABASE_URL=jdbc:postgresql://localhost:5432/jump
export DATABASE_USERNAME=postgres
export DATABASE_PASSWORD=your_password
```

### 4. Build and Run Locally

```bash
# Build the application
mvn clean package

# Run the application
java -jar target/app-0.0.1-SNAPSHOT.jar
```

Or use Maven:

```bash
mvn spring-boot:run
```

The application will be available at `http://localhost:8080`

### 5. Deployment to Fly.io

1. Install Fly.io CLI: https://fly.io/docs/getting-started/installing-flyctl/

2. Login to Fly.io:
```bash
fly auth login
```

3. Create a Fly.io app:
```bash
fly launch
```

4. Create a PostgreSQL database:
```bash
fly postgres create --name jump-db
```

5. Attach the database to your app:
```bash
fly postgres attach --app jump-email-sorter jump-db
```

6. Set secrets:
```bash
fly secrets set GOOGLE_CLIENT_ID=your_client_id
fly secrets set GOOGLE_CLIENT_SECRET=your_client_secret
fly secrets set OPENAI_API_KEY=your_openai_key
```

7. Update OAuth redirect URI in Google Cloud Console:
   - Add: `https://your-app-name.fly.dev/login/oauth2/code/google`

8. Build and deploy:
```bash
mvn clean package
fly deploy
```

## Usage

1. **Sign In**: Click "Sign in with Google" and authorize the application
2. **Create Categories**: Add categories with descriptions (e.g., "Work", "Personal", "Shopping")
3. **Automatic Sorting**: New emails are automatically fetched, categorized, summarized, and archived
4. **View Categories**: Click on a category to see all emails in that category
5. **Bulk Actions**: Select emails and delete or unsubscribe in bulk

## Important Notes

- **Test User**: For development, you must add `webshookeng@gmail.com` as a test user in Google Cloud Console OAuth consent screen
- **Email Scopes**: Apps with Gmail scopes require security review for production use. For testing, use test users in the OAuth consent screen
- **Scheduled Tasks**: The app checks for new emails every minute. Adjust in `EmailProcessingService.java` if needed
- **Unsubscribe Feature**: The unsubscribe feature requires browser automation (Selenium/Playwright) for full functionality. Currently, it extracts unsubscribe links and provides instructions.

## Development

### Project Structure

```
src/
├── main/
│   ├── java/jump/email/app/
│   │   ├── config/          # Security and OAuth configuration
│   │   ├── controller/      # REST controllers
│   │   ├── entity/          # JPA entities
│   │   ├── repository/      # JPA repositories
│   │   └── service/         # Business logic services
│   └── resources/
│       ├── templates/       # Thymeleaf templates
│       └── application.properties
└── test/                    # Test files
```

## License

This project is for demonstration purposes.
