@Typed
package com.chocolatey.pmsencoder

import net.pms.io.OutputParams
import org.apache.http.NameValuePair

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

// XXX some (most? all?) of these DSL properties could just be exposed/documented as-is i.e.
// log.info(..), http.get(...) &c.

/*

    XXX squashed bug: note that delegated methods (i.e. methods exposed via the @Delegate
    annotation) must be *public*:

        All public instance methods present in the type of the annotated field
        and not present in the owner class will be added to owner class
        at compile time.

    http://groovy.codehaus.org/api/groovy/lang/Delegate.html
*/

class ProfileDelegate {
    private final Map<String, String> httpCache = [:] // only needed/used by this.scrape()
    private final Map<String, Document> jsoupCache = [:]
    // FIXME: sigh: transitive delegation doesn't work (groovy bug)
    // so make this public so dependent classes can manually delegate to it
    @Delegate Matcher matcher
    Command command

    public ProfileDelegate(Matcher matcher, Command command) {
        this.matcher = matcher
        this.command = command
    }

    // DSL properties

    /*
        XXX Groovy fail: http://jira.codehaus.org/browse/GROOVY-2500

        if two or more setters for a property (e.g. $DOWNLOADER) are defined (e.g. one for String and another
        for List<String>) Groovy/Groovy++ only uses one of them, complaining at runtime that
        it can't cast e.g. a String into a List:

            Cannot cast object '/usr/bin/downloader string http://downloader.string'
            with class 'java.lang.String' to class 'java.util.List'

        workaround: define just one setter and determine the type with instanceof (via stringList)
    */

    // DSL accessor ($DOWNLOADER): getter
    public List<String> get$DOWNLOADER() {
        command.downloader
    }

    // DSL accessor ($DOWNLOADER): setter
    public List<String> set$DOWNLOADER(Object downloader) {
        command.downloader = Util.stringList(downloader)
    }

    // DSL accessor ($TRANSCODER): getter
    public List<String> get$TRANSCODER() {
        command.transcoder
    }

    // DSL accessor ($TRANSCODER): setter
    public List<String> set$TRANSCODER(Object transcoder) {
        command.transcoder = Util.stringList(transcoder)
    }

    // DSL accessor ($HOOK): getter
    public List<String> get$HOOK() {
        command.hook
    }

    // DSL accessor ($HOOK): setter
    public List<String> set$HOOK(Object hook) {
        command.hook = Util.stringList(hook)
    }

    // DSL accessor ($OUTPUT): getter
    public List<String> get$OUTPUT() {
        command.output
    }

    // DSL accessor ($OUTPUT): setter
    public List<String> set$OUTPUT(Object args) {
        command.output = Util.stringList(args)
    }

    private String getProtocol(Object u) {
        if (u != null) {
            return uri(u.toString()).scheme
        } else {
            return null
        }
    }

    // $PROTOCOL: getter
    public String get$PROTOCOL() {
        return getProtocol(command.getVar('$URI'))
    }

    // $PROTOCOL: setter
    public String set$PROTOCOL(Object newProtocol) {
        def u = command.getVar('$URI')
        def oldProtocol = getProtocol(u)

        if (oldProtocol) { // not null and not empty
            if (newProtocol == null) {
                newProtocol = ''
            }
            u = newProtocol.toString() + u.substring(oldProtocol.length())
            command.setVar('$URI', u)
        }
    }

    // DSL accessor ($PARAMS): getter
    // $PARAMS: getter
    public OutputParams get$PARAMS() {
        command.params
    }

    // DSL getter
    public Object propertyMissing(String name) {
        if (matcher.hasVar(name)) {
            return matcher.getVar(name)
        } else {
            return command.getVar(name)
        }
    }

    // DSL setter
    public String propertyMissing(String name, Object value) {
        command.let(name, value?.toString())
    }

    // DSL method
    // delegated to so must be public
    public URI uri() {
        uri(command.getVar('$URI'))
    }

    // DSL method
    // delegated to so must be public
    public URI uri(Object u) {
        new URI(u?.toString())
    }

    // DSL method - can be called from a pattern or an action.
    // actions inherit this method, whereas patterns add the
    // short-circuiting behaviour and delegate to this via super.scrape(...)
    // XXX: we need to declare these two signatures explicitly to work around
    // issues with @Delegate and default parameters
    public Function1<Object, Boolean> scrape(Map options) { // curry
        return { Object regex -> scrape(options, regex) }
    }

    // DSL method
    public boolean scrape(Object regex) {
        return scrape([:], regex)
    }

    /*
        1) get the URI pointed to by options['uri'] or command.getVar('$URI') (if it hasn't already been retrieved)
        2) perform a regex match against the document
        3) update the stash with any named captures
    */

    // DSL method
    public boolean scrape(Map options, Object regex) {
        String uri = (options['uri'] == null) ? command.getVar('$URI') : options['uri']
        String document = (options['source'] == null) ? httpCache[uri] : options['source']
        boolean decode = options['decode'] == null ? false : options['decode']

        def newStash = new Stash()
        def scraped = false

        if (document == null) {
            log.debug("getting $uri")
            assert http != null
            document = httpCache[uri] = http.get(uri)
        }

        if (document == null) {
            log.error('document not found')
            return scraped
        }

        if (decode) {
            log.debug("URL-decoding content of $uri")
            document = URLDecoder.decode(document)
        }

        log.debug("matching content of $uri against $regex")

        if (RegexHelper.match(document, regex, newStash)) {
            log.debug('success')
            newStash.each { name, value -> command.let(name, value) }
            scraped = true
        } else {
            log.debug('failure')
        }

        return scraped
    }

    // DSL method
    public Function1<Object, Elements> $(Map options) { // curry
        return { Object query -> $(options, query) }
    }

    // DSL method
    public Elements $(Object query) {
        $([:], query)
    }

    // DSL method
    public Elements $(Map options, Object query) {
        def jsoup

        if (options['source']) {
            jsoup = getJsoupForString(options['source'].toString())
        } else if (options['uri']) {
            jsoup = getJsoupForUri(options['uri'].toString())
        } else {
            jsoup = getJsoupForUri(command.getVar('$URI'))
        }

        return jsoup.select(query.toString())
    }

    // DSL method
    // spell these out (no default parameters) to work arounbd Groovy bugs
    public Document jsoup() {
        jsoup([:])
    }

    // DSL method
    public Document jsoup(Map options) {
        def jsoup

        if (options['source']) {
            jsoup = getJsoupForString(options['source'].toString())
        } else if (options['uri']) {
            jsoup = getJsoupForUri(options['uri'].toString())
        } else {
            jsoup = getJsoupForUri(command.getVar('$URI'))
        }

        return jsoup
    }

    private Document getJsoupForUri(Object obj) {
        def uri = obj.toString()
        def cached = httpCache[uri] ?: (httpCache[uri] = http.get(uri))
        return getJsoupForString(cached)
    }

    private Document getJsoupForString(Object obj) {
        def string = obj.toString()
        return jsoupCache[string] ?: (jsoupCache[string] = Jsoup.parse(string))
    }
}
