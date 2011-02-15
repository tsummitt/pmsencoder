@Typed
package com.chocolatey.pmsencoder

class ProtocolMethodTest extends PMSEncoderTestCase {
    void setUp() {
        super.setUp()
        def script = this.getClass().getResource('/protocol_method.groovy')
        matcher.load(script)
    }

    void testProtocolString() {
        assertMatch([
            uri: 'dvb://www.example.com',
            matches: [ 'Protocol String' ]
        ])
    }

    void testProtocolList() {
        assertMatch([
            uri: 'sop://www.example.com',
            matches: [ 'Protocol List' ]
        ])
    }

    void testProtocolNoMatch() {
        assertMatch([
            uri: 'http://www.example.com',
            matches: []
        ])
    }
}