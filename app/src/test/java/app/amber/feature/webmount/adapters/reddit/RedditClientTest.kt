package app.amber.feature.webmount.adapters.reddit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditClientTest {

    @Test
    fun postWithCommentsDropsMoreContinuationNodes() = runBlocking {
        val response = """
            [
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t3",
                      "data": {
                        "id": "post-1",
                        "subreddit": "Android",
                        "author": "poster",
                        "title": "Title",
                        "url": "https://example.com",
                        "permalink": "/r/Android/comments/post-1/title/",
                        "selftext": "Body",
                        "score": 10,
                        "num_comments": 2,
                        "created_utc": 1000,
                        "is_self": true,
                        "over_18": false
                      }
                    }
                  ]
                }
              },
              {
                "kind": "Listing",
                "data": {
                  "children": [
                    {
                      "kind": "t1",
                      "data": {
                        "id": "comment-1",
                        "author": "commenter",
                        "body": "A comment",
                        "score": 4,
                        "created_utc": 1001,
                        "permalink": "/r/Android/comments/post-1/title/comment-1/",
                        "parent_id": "t3_post-1"
                      }
                    },
                    {
                      "kind": "more",
                      "data": {
                        "count": 1,
                        "children": ["comment-2"]
                      }
                    }
                  ]
                }
              }
            ]
        """.trimIndent()
        val http = HttpClient(MockEngine {
            respond(
                content = response,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        try {
            val detail = RedditClient(http).postWithComments("/r/Android/comments/post-1/title")

            assertTrue(detail.post is RedditChild.Post)
            assertEquals(1, detail.comments.size)
            assertTrue(detail.comments.single() is RedditChild.Comment)
            assertEquals("comment-1", detail.comments.single().id)
            assertFalse(detail.comments.any { it is RedditChild.Post })
        } finally {
            http.close()
        }
    }
}
