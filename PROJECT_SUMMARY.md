# Project Summary: reitit-extras

## Overview
A Clojure library providing additional utilities and middleware extensions for the Reitit router. The library focuses on practical server-side rendering (SSR) utilities, context injection, resource handling with caching, HTML rendering with Hiccup, comprehensive exception handling, and testing utilities for HTTP servers.

**Version**: 0.2.2  
**License**: MIT  
**Published**: Clojars as `io.github.abogoyavlensky/reitit-extras`

## Key File Structure

```
├── src/reitit_extras/
│   ├── core.clj           # Main library with middleware and handlers
│   └── tests.clj          # Testing utilities for HTTP servers
├── test/reitit_extras/
│   └── core_test.clj      # Unit tests
├── dev/
│   └── user.clj           # Development namespace with REPL utilities
├── deps.edn               # Project dependencies and build configuration
├── bb.edn                 # Babashka task definitions
└── lefthook.yml           # Git hooks configuration
```

## Core Dependencies

### Runtime Dependencies (deps.edn)
- **Clojure 1.12.1** - Base language
- **Reitit ecosystem** (v0.9.1):
  - `metosin/reitit-ring` - Ring integration
  - `metosin/reitit-middleware` - Middleware support
  - `metosin/reitit-malli` - Schema validation with Malli
  - `metosin/reitit-dev` - Development utilities
- **Ring ecosystem**:
  - `ring/ring-defaults` (0.6.0) - Common Ring middleware
  - `amalloy/ring-gzip-middleware` (0.1.4) - Gzip compression
- **Utilities**:
  - `hiccup/hiccup` (2.0.0) - HTML generation
  - `org.clojure/tools.logging` (1.3.0) - Logging

### Development Dependencies
- **Testing**: `eftest/eftest` (0.6.0), `cloverage/cloverage` (1.2.4)
- **Development**: `ring/ring-devel` (1.14.2)
- **Maintenance**: `com.github.liquidz/antq` (2.11.1269) - dependency updates

## Available APIs and Functions

### Middleware (`reitit-extras.core`)

#### Context Management
```clojure
(wrap-context handler context)
; Add system dependencies to request as :context key
; Usage: Inject database connections, config, etc.
```

#### Development Utilities
```clojure
(wrap-reload f)
; Reload handler on every request (dev mode only)
; Automatically requires ring.middleware.reload
```

#### Exception Handling
```clojure
exception-middleware
; Comprehensive exception middleware with logging
; Provides structured error responses with type, path, error data
```

#### Parameter Coercion
```clojure
non-throwing-coerce-request-middleware
; Validates/coerces request parameters without throwing exceptions
; Adds :errors key to request instead of throwing
; Based on reitit.ring.coercion but more lenient
```

### URL Generation
```clojure
(route router route-name)
(route router route-name {:keys [path query]})
; Generate URLs from route names with optional parameters
```

### Security Utilities
```clojure
(csrf-token)          ; Get CSRF token value
(csrf-token-html)     ; Generate hidden CSRF input field
(csrf-token-json)     ; Generate CSRF token as JSON string
(wrap-xss-protection handler) ; XSS protection middleware
```

### Resource Handling
```clojure
(create-resource-handler-cached opts)
; Create resource handler with configurable caching
; Options: :cached?, :cache-control
; Default cache: "public,max-age=31536000,immutable"
```

### HTML Rendering
```clojure
(render-html content)
; Render Hiccup content as HTML response
; Adds DOCTYPE and Content-Type headers
```

### Server-Side Rendering Handler (Deprecated)
```clojure
(handler-ssr config context)
(get-handler-ssr config context)
; Complete SSR handler with all middleware stack
; Includes security headers, sessions, CSRF, coercion
; Note: Deprecated - prefer defining server directly in projects
```

### Testing Utilities (`reitit-extras.tests`)

#### Server URL Generation
```clojure
(get-server-url server)           ; Default to :host (localhost)
(get-server-url server :host)     ; http://localhost:port
(get-server-url server :container) ; http://host.testcontainers.internal:port
```

#### Session Management for Tests
```clojure
(encrypt-session-to-cookie session-data secret-key)
(decrypt-session-from-cookie session-value secret-key)
(session-cookies session-data secret-key)
; Utilities for testing authenticated endpoints
```

#### CSRF Testing Constants
```clojure
CSRF-TOKEN-FORM-KEY    ; :__anti-forgery-token
CSRF-TOKEN-SESSION-KEY ; :ring.middleware.anti-forgery/anti-forgery-token
CSRF-TOKEN-HEADER      ; "X-CSRF-Token"
```

## Architecture and Design Patterns

### Middleware Stack Pattern
The library follows Ring's middleware pattern with a complete, opinionated stack for SSR applications:
1. Security headers (XSS, CSRF, content-type options)
2. SSL/HTTPS enforcement
3. Static resource handling with caching
4. Session management with secure cookies
5. Parameter parsing and coercion
6. Exception handling with structured responses

### Context Injection Pattern
Uses dependency injection through middleware to make system components available to handlers:
```clojure
; Inject database, config, etc.
[wrap-context {:db db-conn :config app-config}]
```

### Non-Throwing Validation
Implements graceful parameter validation that adds errors to the request rather than throwing exceptions, allowing handlers to respond appropriately.

### Testing Utilities Pattern
Provides utilities specifically for testing HTTP servers, including session encryption/decryption and URL generation for different environments (local vs containers).

## Development Workflow

### Available Babashka Tasks
```bash
bb tasks                 # List all available tasks
bb deps                  # Install dependencies
bb fmt / bb fmt-check    # Code formatting
bb lint                  # Code linting with clj-kondo
bb test                  # Run tests with eftest
bb check                 # Run all checks and tests
bb outdated             # Check/upgrade dependencies
bb build                # Build locally
bb deploy-snapshot      # Deploy to Clojars (snapshot)
bb deploy-release       # Deploy to Clojars (release)
bb release              # Create git tag and deploy
```

### Git Hooks (lefthook)
Pre-push hooks run:
- Code formatting check
- Linting
- Tests
- Outdated dependency check

### REPL Development (`dev/user.clj`)
```clojure
(reset)           ; Reload changed namespaces
(run-all-tests)   ; Run all tests after reload
```

## Implementation Conventions

### Code Style
- Single semicolon (;) for comments in Clojure
- Uses standard Clojure naming conventions
- Comprehensive docstrings for public functions
- Explicit namespace aliases

### Security Patterns
- Secure session cookies (http-only, secure flags)
- CSRF protection with token utilities
- XSS protection headers
- Content Security Policy headers
- MD5-based session key generation

### Error Handling
- Structured error responses with type, path, and details
- Logging integration with clojure.tools.logging
- Non-throwing validation approach

## Extension Points

### Custom Middleware
Easy to extend the middleware stack by adding to the `:middleware` vector in handler configuration.

### Custom Exception Handlers
Extend `exception-middleware` by merging additional handlers into the exception middleware map.

### Custom Resource Handlers
Override caching behavior by providing custom `:cache-control` values to `create-resource-handler-cached`.

### Testing Extensions
The testing utilities can be extended for different server types or authentication mechanisms.

## Build and Deployment

### Local Development
- Uses `mise.jdx.dev` for tool management
- Babashka for task automation
- eftest for testing
- clj-kondo for linting
- cljfmt for formatting

### CI/CD Integration
- GitHub Actions for automated testing and deployment
- Automatic snapshot deployment on master branch
- Release deployment via git tags
- Clojars integration with environment variables

### Dependency Management
- Regular dependency updates via antq
- Version management through deps.edn aliases
- Slim build tool for packaging