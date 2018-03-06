# geeny/fetch

A Clojure library for making HTTP requests in your ring handler.

## Rationale

As we move to (micro)service based architectures using HTTP endpoints, it becomes common place for a service to have to request information from an upstream HTTP API. In doing so, we found that we needed to repeat two things on top of each http-kit function call:

  1) There are some important headers in the current request you are serving that you want to pass along. This can vary from JWT authorization tokens to session IDs. You need to destructure those from the request body, which you then had to make sure is passed all the way down into your function from the base handler
  2) You want to respond in a uniform way to problems in error handling. Typically you do not care if you've lost connectivity or the service crashed

Fixing these in simple utility functions quickly becomes cumbersome. It needs to be possible to get stuff out from the request object, which must be dynamically passed to the function. Also, you end up re-implementing common sense things all over again for the next HTTP verb.

Wouldn't it be much easier if you could just add a middleware, and not have to worry about any of that? Enter fetch. Wrap your ring handler, and have a function callable from anywhere that you only need to give a URL to work

## Making fetch happen

You need to add fetch's middleware to your Ring server. Additionally, you can set up specific error handling for fetch failures (such as when you want to hide the upstream error to your client).

### Adding the middleware

```clojure
(ns my-app.middleware
  (:require [fetch.core :refer [wrap-add-fetch]]
            [ring.middleware.defaults :refer [site-defaults wrap-defaults]]))

(defn wrap-base [handler]
  (-> handler
      wrap-defaults
      ;; etc
      wrap-add-fetch))
```
Or alternatively, if you want to specify which headers should be set upstream

```clojure
(defn wrap-base [handler]
  (-> handler
      wrap-defaults
      ;; etc
      (wrap-add-fetch ["authorization" "content-type"])))
```

### Making a call


Dummy functions have been defined in the fetch.core ns that will be re-bound when you make the call. So:

```clojure
(ns foo.handler
(:require [fetch.core :as f]))

(defn- get-upstream-data
  (f/get-sync "https://labs.geeny.io/api/applications"))

(defn my-wonderful-handler []
  (get-upstream-data))
```

### When you can't make fetch happen

In the case of a failure, fetch will throw an `ex-info`, with e.g.:

```clojure
{:type   ::fetch.core/fetch
 :url    "http://example.com"
 :status 404
 :error  nil}
```
The `:error` key refers to the http-kit error (an exception object). Typically, you expect to either get a non successful `:status` code, or an error.

If you are using e.g. plain Compojure, you probably have middleware already for dealing with these exceptions. In that case, you should extend that to catch clojure.lang.ExceptionInfo, or use a library such as [catch-data](https://github.com/gfredericks/catch-data).

If you're using Compojure API, you can add the exception handler directly into your `:exceptions` map, like so:

```clojure
(ns consumer-marketplace.routes
  (:require [compojure.api.exception :as ex]
            [compojure.api.sweet :refer :all]
            [fetch.core :as f]))

(defn exception-response [status message]
  {:status status
   :body {:message message
          :type :fetch}
   :headers {}})

(defn fetch-exception-handler []
  (fn [^Exception e {:keys [status url error] :as data} request]
    (timbre/error e)
    (timbre/error error)
    (case status
      ;; put your custom errors here
      404 (exception-response status "Not found")
      422 (exception-response status (.getMessage e))
      nil (exception-response 500 "Server internal error")
      (exception-response 403 "Forbidden"))))

;; your routes
(defapi routes
 :exceptions
 {:handlers
   {::ex/default (exception-response 500 "We didn't anticipate this!")
    ::f/fetch    (fetch-exception-handler)}}

 (context "/api" []
   :tags ["api"]
   (GET "/" []
    :return {:result String}
    (ok {:result "hello, world"}))))
```


## TODO
As always, there are improvements to be made.

  1) Whilst we test these functions in our own production code regularly, there are no tests done inside this repo yet
  2) Instead of a dummy function, it would be good to have working HTTP functions that can then be called in the context of REPL driven development
  3) This seems like an obvious surface area to add the ability to drop-in caching


## License

Copyright (C) 2017 Telefónica Germany Next GmbH, Charlottenstrasse 4, 10969 Berlin.

This project is licensed under the terms of the Mozilla Public License Version 2.0.
