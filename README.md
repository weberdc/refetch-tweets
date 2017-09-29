# refetch-tweets

Author: **Derek Weber**

Last updated: **2017-09-29**

A tool to refetch tweets to update knowledge of their metadata.


## Description

This app reads in a file of tweets (one JSON object per line) and then refetches
each of the tweets, adds a `"collected_at"` timestamp to their JSON, and writes
them out to the end of another file. In this way, this app can be used to detect
when the metadata of the tweets change (e.g., retweet or favourite counts).
Twitter credentials are looked for in `"./twitter.properties"`, and proxy info
is looked for in `"./proxy.properties"`. Commandline options for the input file,
the output file, and the Twitter properties are provided, along with a verbose
mode.


## Requirements:

 + Java Development Kit 1.8
 + [Apache Groovy](http://groovy-lang.org) (Apache 2.0 licence)
 + [Twitter4J](http://twitter4j.org) (Apache 2.0 licence)
   + depends on [JSON](http://json.org) ([JSON licence](http://www.json.org/license.html))
 + [FasterXML](http://wiki.fasterxml.com/JacksonHome) (Apache 2.0 licence)
 + [jcommander](http://jcommander.org) (Apache 2.0 licence)

Built with [Gradle 4.2](http://gradle.org), included via the wrapper.


## To Build

The Gradle wrapper has been included, so it's not necessary for you to have
Gradle installed - it will install itself as part of the build process. All that
is required is the Java Development Kit.

By running

`$ ./gradlew installDist` or `$ gradlew.bat installDist`

you will create an installable copy of the app in `PROJECT_ROOT/build/refetch-tweets`.

Use the target `distZip` to make a distribution in `PROJECT_ROOT/build/distributions`
or the target `timestampedDistZip` to add a timestamp to the distribution archive
filename.

To also include your own local `twitter.properties` file with your Twitter
credentials, use the target `privilegedDistZip` to make a special distribution
in `PROJECT_ROOT/build/distributions` that starts with.


## Configuration

Twitter OAuth credentials must be available in a properties file based on the
provided `twitter.properties-template` in the project's root directory. Copy the
template file to a properties file (the default is `twitter.properties` in the
same directory), and edit it with your Twitter app credentials. For further
information see [http://twitter4j.org/en/configuration.html]().

If running the app behind a proxy or filewall, copy the
`proxy.properties-template` file to a file named `proxy.properties` and set the
properties inside to your proxy credentials. If you feel uncomfortable putting
your proxy password in the file, leave the password-related ones commented and
the app will ask for the password.


## Usage
If you've just downloaded the binary distribution, do this from within the
unzipped archive (i.e. in the `refetch-tweets` directory). Otherwise, if you've
just built the app from source, do this from within
`PROJECT_ROOT/build/install/refetch-tweets`:

<pre>
Usage: bin/refetch-tweets[.bat] [options]
  Options:
    -c, --credentials
      Properties file with Twitter OAuth credentials
      Default: ./twitter.properties
    -h, -?, --help
      Help
      Default: false
    -o, --outfile
      File to update with refetched tweets
      Default: ./updated-tweets.json
    -i, --seed-tweets
      File of seed tweets to update
      Default: ./tweets.json
    -v, --debug, --verbose
      Debug mode
      Default: false
</pre>

Run the app referring to your file of seed tweets:
<pre>
prompt> bin/refetch-tweets \
    --seed-tweets data/test/test-input-100-2.json \
    --outfile ./test-output.json \
    --debug
</pre>

This will create a file `test-output.json` which will contain updated versions
of the tweets in the input file, each with an extra field: `"collected_at"`.
This field is a timestamp formatted the same way as the `"created_at"` field.
**N.B.** Be aware that the output file will end up with multiple copies of the
same tweets if this app is called repeatedly; each copy will have its own value
for `"collected_at"` and their metadata (e.g., retweet and favourite counts) may
have changed, so they will be distinguishable.

As an example of what to do with the output file, you could pull out the ids,
collected timestamps and retweet and favourite counts to begin with. I recommend
using `jq`. E.g.

<pre>
prompt>  cat updated-tweets.json | \
    jq '.|"\"\(.id_str)\",\(.collected_at),\(.retweet_count),\(.favorite_count)"' | \
    sort | \
    sed 's/^\"//' | sed 's/\"$//' > updated-tweet-metadata.csv
</pre>

## Rate limits

Attempts have been made to account for Twitter's rate limits, so at times the
app will pause, waiting until the rate limit has refreshed. It reports how long
it will wait when it does have to pause.
